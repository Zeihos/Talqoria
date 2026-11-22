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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class TerritoryStoreTest {
    @TempDir Path temp;

    private Path main() { return temp.resolve("territories.xml"); }
    private Path backup() { return temp.resolve("territories.xml.bak"); }
    private TerritoryStore open() throws IOException { return new TerritoryStore(main(), 8, 8, 16); }
    private static TerritoryStore.ChunkKey key() {
        return new TerritoryStore.ChunkKey(UUID.randomUUID(), 14, -2);
    }
    private static void edit(Path path, String name, String value) throws Exception {
        Properties data = new Properties();
        try (InputStream in = Files.newInputStream(path)) { data.loadFromXML(in); }
        if (value == null) data.remove(name); else data.setProperty(name, value);
        try (OutputStream out = Files.newOutputStream(path)) { data.storeToXML(out, "test input", "UTF-8"); }
    }

    @Test
    void ownerMembersGuestsAndClaimsSurviveRestart() throws Exception {
        UUID owner = UUID.randomUUID(), member = UUID.randomUUID(), guest = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        TerritoryStore.ChunkKey chunk = key();
        TerritoryStore store = open();
        assertEquals(TerritoryStore.Result.OK, store.create(owner, "Северный берег"));
        assertEquals(TerritoryStore.Result.OK, store.claim(owner, chunk));
        assertTrue(store.canBuild(owner, chunk));
        assertFalse(store.canBuild(outsider, chunk));
        assertEquals(TerritoryStore.Result.OK, store.addMember(owner, member));
        assertEquals(TerritoryStore.Result.OK, store.setOfficer(owner, member, true));
        assertEquals(TerritoryStore.Result.OK, store.trust(owner, guest, "Visitor007"));
        assertTrue(store.canBuild(member, chunk));
        assertTrue(store.canBuild(guest, chunk));
        assertFalse(store.canBuild(outsider, chunk));
        store.save();

        TerritoryStore restored = open();
        assertFalse(restored.recoveredBackup());
        assertTrue(restored.isProtected(chunk));
        assertEquals("OFFICER", restored.townFor(member).role(member));
        assertEquals("OWNER", restored.townFor(owner).role(owner));
        assertTrue(restored.canBuild(guest, chunk));
        assertFalse(restored.canBuild(outsider, chunk));
        assertEquals(TerritoryStore.Result.OK, restored.untrust(owner, "Visitor007"));
        assertFalse(restored.canBuild(guest, chunk));
        restored.save();
        assertFalse(open().canBuild(guest, chunk));
    }

    @Test
    void revertedPrimaryCannotEraseAProtectedClaim() throws Exception {
        UUID owner = UUID.randomUUID();
        TerritoryStore.ChunkKey chunk = key();
        TerritoryStore store = open();
        assertEquals(TerritoryStore.Result.OK, store.create(owner, "Восточный город"));
        store.save(); // revision 1, no claim
        byte[] oldPrimary = Files.readAllBytes(main());
        assertEquals(TerritoryStore.Result.OK, store.claim(owner, chunk));
        store.save(); // revision 2 with claim
        Files.copy(main(), backup(), StandardCopyOption.REPLACE_EXISTING);
        byte[] safeBackup = Files.readAllBytes(backup());
        Files.write(main(), oldPrimary); // syntactically valid but stale revision 1

        TerritoryStore restored = open();
        assertTrue(restored.recoveredBackup(), "Outdated primary must lock claim edits");
        assertTrue(restored.isProtected(chunk), "Backup claim must not silently disappear");
        assertFalse(restored.canBuild(owner, chunk), "Recovered store fails closed to all");
        assertEquals(TerritoryStore.Result.STORAGE_LOCKED, restored.unclaim(owner, chunk));
        assertEquals(TerritoryStore.Result.STORAGE_LOCKED, restored.create(UUID.randomUUID(), "Третий город"));
        assertThrows(IOException.class, restored::save);
        assertArrayEquals(safeBackup, Files.readAllBytes(backup()));
    }

    @Test
    void checksumMismatchRecoversReadOnly() throws Exception {
        UUID owner = UUID.randomUUID();
        TerritoryStore.ChunkKey chunk = key();
        TerritoryStore store = open();
        store.create(owner, "Хранители");
        store.claim(owner, chunk);
        store.save();
        Files.copy(main(), backup(), StandardCopyOption.REPLACE_EXISTING);
        edit(main(), "claims.count", "0"); // still valid XML, wrong SHA-256
        TerritoryStore restored = open();
        assertTrue(restored.recoveredBackup());
        assertTrue(restored.isProtected(chunk));
        assertFalse(restored.canBuild(owner, chunk));
    }

    @Test
    void backupCorruptionFailsClosedEvenWhenPrimaryIsReadable() throws Exception {
        TerritoryStore store = open();
        store.create(UUID.randomUUID(), "Первый город");
        store.save();
        Files.writeString(backup(), "broken xml");
        assertThrows(IOException.class, this::open);
    }

    @Test
    void legitimateUnclaimAndDisbandAreNotAccidentalRollbacks() throws Exception {
        UUID owner = UUID.randomUUID();
        TerritoryStore.ChunkKey chunk = key();
        TerritoryStore store = open();
        store.create(owner, "Свободные земли");
        store.claim(owner, chunk);
        store.save();
        assertEquals(TerritoryStore.Result.OK, store.unclaim(owner, chunk));
        store.save();
        TerritoryStore unclaimed = open();
        assertFalse(unclaimed.recoveredBackup());
        assertFalse(unclaimed.isProtected(chunk));
        assertEquals(TerritoryStore.Result.OK, unclaimed.disband(owner));
        unclaimed.save();
        TerritoryStore empty = open();
        assertFalse(empty.recoveredBackup());
        assertTrue(empty.towns().isEmpty());
    }

    @Test
    void primaryMissingButBackupExistsLocksEdits() throws Exception {
        TerritoryStore store = open();
        UUID owner = UUID.randomUUID();
        store.create(owner, "Север");
        store.save();
        Files.copy(main(), backup(), StandardCopyOption.REPLACE_EXISTING);
        Files.delete(main());
        TerritoryStore recovered = open();
        assertTrue(recovered.recoveredBackup());
        assertEquals(TerritoryStore.Result.STORAGE_LOCKED, recovered.claim(owner, key()));
        assertFalse(Files.exists(main()));
    }

    @Test
    void sealedSameRevisionButDifferentContentIsNotTrusted() throws Exception {
        TerritoryStore store = open();
        store.create(UUID.randomUUID(), "Город один");
        store.save();
        Path otherDir = Files.createDirectory(temp.resolve("other"));
        Path otherFile = otherDir.resolve("territories.xml");
        TerritoryStore other = new TerritoryStore(otherFile, 8, 8, 16);
        other.create(UUID.randomUUID(), "Город два");
        other.save();
        Files.copy(otherFile, backup(), StandardCopyOption.REPLACE_EXISTING);
        TerritoryStore recovered = open();
        assertTrue(recovered.recoveredBackup(), "Equal revision with different checksums must lock");
        assertEquals("Город два", recovered.towns().iterator().next().name());
    }

    @Test
    void legacySnapshotWithoutRevisionOrSealStillLoadsAndMigrates() throws Exception {
        TerritoryStore store = open();
        UUID owner = UUID.randomUUID();
        store.create(owner, "Старый город");
        store.save();
        edit(main(), "snapshot.revision", null);
        edit(main(), "integrity.sha256", null);
        TerritoryStore legacy = open();
        assertFalse(legacy.recoveredBackup());
        assertNotNull(legacy.townFor(owner));
        legacy.save();
        Properties newer = new Properties();
        try (InputStream in = Files.newInputStream(main())) { newer.loadFromXML(in); }
        assertEquals("1", newer.getProperty("snapshot.revision"));
        assertNotNull(newer.getProperty("integrity.sha256"));
        assertFalse(open().recoveredBackup());
    }

    @Test
    void staffBypassNeverOverridesStorageLock() {
        assertTrue(ClaimAccessPolicy.denyEdit(true, true, false, true));
        assertTrue(ClaimAccessPolicy.denyEdit(true, false, false, true));
        assertTrue(ClaimAccessPolicy.denyEdit(false, false, true, true));
        assertTrue(ClaimAccessPolicy.denyEdit(false, false, false, false));
        assertFalse(ClaimAccessPolicy.denyEdit(false, true, true, false));
        assertFalse(ClaimAccessPolicy.denyEdit(false, false, false, true));
    }
    @Test
    void automatedTransferIsDeniedAcrossDifferentTowns() throws Exception {
        TerritoryStore store = open();
        UUID ownerOne = UUID.randomUUID(), ownerTwo = UUID.randomUUID(), world = UUID.randomUUID();
        var left = new TerritoryStore.ChunkKey(world, 0, 0);
        var right = new TerritoryStore.ChunkKey(world, 1, 0);
        var wild = new TerritoryStore.ChunkKey(world, 2, 0);
        assertEquals(TerritoryStore.Result.OK, store.create(ownerOne, "Берег один"));
        assertEquals(TerritoryStore.Result.OK, store.create(ownerTwo, "Берег два"));
        assertEquals(TerritoryStore.Result.OK, store.claim(ownerOne, left));
        assertEquals(TerritoryStore.Result.OK, store.claim(ownerTwo, right));
        assertNull(store.claimOwner(wild));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false,
            store.claimOwner(left), store.claimOwner(right)));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false,
            store.claimOwner(right), store.claimOwner(wild)));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false,
            store.claimOwner(wild), store.claimOwner(left)));
        store.save();
        TerritoryStore loaded = open();
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false,
            loaded.claimOwner(left), loaded.claimOwner(right)));
    }

    @Test
    void automatedTransferWithinOneTownIsAllowedUntilClaimsChange() throws Exception {
        TerritoryStore store = open();
        UUID owner = UUID.randomUUID(), world = UUID.randomUUID();
        var left = new TerritoryStore.ChunkKey(world, 0, 0);
        var right = new TerritoryStore.ChunkKey(world, 1, 0);
        assertEquals(TerritoryStore.Result.OK, store.create(owner, "Одна долина"));
        assertEquals(TerritoryStore.Result.OK, store.claim(owner, left));
        assertEquals(TerritoryStore.Result.OK, store.claim(owner, right));
        assertFalse(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false,
            store.claimOwner(left), store.claimOwner(right)));
        assertEquals(TerritoryStore.Result.OK, store.unclaim(owner, right));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false,
            store.claimOwner(left), store.claimOwner(right)));
    }

}
