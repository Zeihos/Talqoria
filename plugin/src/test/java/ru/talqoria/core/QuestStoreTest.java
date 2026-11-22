package ru.talqoria.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression tests using the real XML serializer and the production daily quest logic. */
public final class QuestStoreTest {
    @TempDir Path temp;
    private final AtomicLong clock = new AtomicLong(21_000);
    private Path file() { return temp.resolve("quests.xml"); }
    private Path backup() { return temp.resolve("quests.xml.bak"); }
    private QuestStore open() throws IOException { return new QuestStore(file(), clock::get); }

    private void savedAndBackedUp(QuestStore store) throws Exception {
        store.save();
        Files.copy(file(), backup(), StandardCopyOption.REPLACE_EXISTING);
    }

    private void rewrite(String field, String value) throws Exception {
        Properties data = new Properties();
        try (InputStream in = Files.newInputStream(file())) { data.loadFromXML(in); }
        if (value == null) data.remove(field); else data.setProperty(field, value);
        try (OutputStream out = Files.newOutputStream(file())) {
            data.storeToXML(out, "Modified snapshot for regression testing", "UTF-8");
        }
    }

    private static void progress(QuestStore store, UUID player, int count) {
        for (int i=0; i<count; i++)
            assertTrue(store.record(player, QuestStore.Objective.STONE));
    }
    private static int stoneProgress(QuestStore store, UUID player) {
        return store.statuses(player).stream()
            .filter(s -> s.quest().id().equals("miner")).findFirst().orElseThrow().progress();
    }

    @Test
    void validProgressAndClaimsSurviveRestart() throws Exception {
        UUID player = UUID.randomUUID();
        QuestStore store = open();
        progress(store, player, 4);
        store.save();
        progress(store, player, 3);
        store.save(); // old primary is backed up automatically
        QuestStore restarted = open();
        assertFalse(restarted.recoveredBackup());
        assertEquals(7, stoneProgress(restarted, player));
        assertEquals(4, new QuestStore(backup(), clock::get).statuses(player).get(0).progress());
        assertTrue(Files.size(backup()) > 0);
    }

    @Test
    void regressionInProgressLocksClaimsAndPreservesBackup() throws Exception {
        UUID player = UUID.randomUUID();
        QuestStore store = open();
        progress(store, player, 12);
        savedAndBackedUp(store);
        byte[] originalBackup = Files.readAllBytes(backup());
        rewrite("player.0.miner.progress", "2");
        QuestStore recovered = open();
        assertTrue(recovered.recoveredBackup(), "Valid XML with older progress must not silently load");
        assertEquals(12, stoneProgress(recovered, player));
        assertFalse(recovered.record(player, QuestStore.Objective.STONE), "Recovered data stays read-only");
        assertThrows(IOException.class, recovered::save);
        assertArrayEquals(originalBackup, Files.readAllBytes(backup()), "Backup remains untouched");
    }

    @Test
    void disappearingPaidClaimCausesReadOnlyRecovery() throws Exception {
        UUID player = UUID.randomUUID();
        QuestStore store = open();
        progress(store, player, 48);
        assertEquals(QuestStore.ClaimResult.READY, store.canClaim(player, "miner"));
        assertTrue(store.markClaimed(player, "miner"));
        savedAndBackedUp(store);
        rewrite("player.0.miner.claimed", "false");
        QuestStore recovered = open();
        assertTrue(recovered.recoveredBackup());
        assertEquals(QuestStore.ClaimResult.ALREADY_CLAIMED, recovered.canClaim(player, "miner"));
        assertFalse(recovered.markClaimed(player, "miner"));
    }

    @Test
    void olderDayCannotReplaceNewerBackup() throws Exception {
        UUID player = UUID.randomUUID();
        QuestStore store = open();
        progress(store, player, 4);
        savedAndBackedUp(store);
        rewrite("player.0.day", Long.toString(clock.get() - 1));
        assertTrue(open().recoveredBackup(), "A rewound day is not a valid daily reset");
    }

    @Test
    void legitimateNextDayResetIsAllowed() throws Exception {
        UUID player = UUID.randomUUID();
        QuestStore store = open();
        progress(store, player, 8);
        store.save();
        clock.incrementAndGet();
        assertEquals(0, stoneProgress(store, player));
        store.save();
        QuestStore restarted = open();
        assertFalse(restarted.recoveredBackup(), "Progress reset on a newer UTC day is allowed");
        assertEquals(0, stoneProgress(restarted, player));
        assertEquals(8, new QuestStore(backup(), () -> clock.get() - 1).statuses(player).get(0).progress());
    }

    @Test
    void missingPlayerDoesNotLookLikeAnEmptyNewWorld() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        QuestStore store = open();
        progress(store, first, 2);
        progress(store, second, 6);
        savedAndBackedUp(store);
        rewrite("players.count", "1");
        QuestStore recovered = open();
        assertTrue(recovered.recoveredBackup());
        assertEquals(2, stoneProgress(recovered, first));
        assertEquals(6, stoneProgress(recovered, second));
    }

    @Test
    void missingPrimaryWithBackupIsReadOnly() throws Exception {
        UUID player = UUID.randomUUID();
        QuestStore store = open();
        progress(store, player, 2);
        savedAndBackedUp(store);
        Files.delete(file());
        QuestStore recovered = open();
        assertTrue(recovered.recoveredBackup());
        assertEquals(2, stoneProgress(recovered, player));
        assertFalse(Files.exists(file()), "Starting in recovery mode must not rewrite missing primary");
        assertThrows(IOException.class, recovered::save);
    }

    @Test
    void invalidBackupFailsClosedEvenWhenPrimaryIsValid() throws Exception {
        UUID player = UUID.randomUUID();
        QuestStore store = open();
        progress(store, player, 2);
        store.save();
        Files.writeString(backup(), "<broken xml");
        assertThrows(IOException.class, this::open,
            "A damaged backup must not be silently treated as trustworthy");
    }

    @Test
    void newPlayerAndIncreasingProgressAreValid() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        QuestStore store = open();
        progress(store, first, 1);
        store.save();
        progress(store, first, 3);
        progress(store, second, 5);
        store.save();
        QuestStore restarted = open();
        assertFalse(restarted.recoveredBackup());
        assertEquals(4, stoneProgress(restarted, first));
        assertEquals(5, stoneProgress(restarted, second));
    }
}
