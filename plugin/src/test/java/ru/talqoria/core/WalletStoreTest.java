package ru.talqoria.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Persistence and anti-duplicate-reward regression tests. Uses real Bukkit YAML. */
public final class WalletStoreTest {
    @TempDir Path temp;

    private WalletStore open(Path file) throws IOException {
        return new WalletStore(file, 100L, 25L, 1_000_000L);
    }
    private static String player(UUID id) { return "players." + id; }
    private static Path backup(Path file) { return file.resolveSibling(file.getFileName() + ".bak"); }
    private static void set(Path file, String key, Object value) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        yaml.set(key, value);
        yaml.save(file.toFile());
    }

    @Test
    void validTransferAndDailyStayConsistentAcrossRestart() throws Exception {
        Path file = temp.resolve("players.yml");
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        WalletStore wallet = open(file);
        assertTrue(wallet.ensurePlayer(alice));
        wallet.save();
        assertEquals(WalletStore.Transfer.OK, wallet.transferAndSave(alice, bob, 30));
        assertEquals(70, wallet.balance(alice));
        assertEquals(130, wallet.balance(bob));
        assertEquals(WalletStore.DailyStatus.OK, wallet.claimDailyAndSave(bob).status());
        assertEquals(WalletStore.DailyStatus.ALREADY, wallet.claimDailyAndSave(bob).status());
        WalletStore loaded = open(file);
        assertFalse(loaded.recoveredBackup());
        assertEquals(70, loaded.balance(alice));
        assertEquals(155, loaded.balance(bob));
        assertEquals(WalletStore.DailyStatus.ALREADY, loaded.claimDailyAndSave(bob).status());
    }

    @Test
    void missingPaidReceiptCausesReadOnlyRecoveryRatherThanDuplicatePayout() throws Exception {
        Path file = temp.resolve("players.yml");
        UUID id = UUID.randomUUID();
        WalletStore wallet = open(file);
        assertEquals(WalletStore.RewardGrant.OK, wallet.creditQuestOnce(id, "2026_miner", 40));
        Files.copy(file, backup(file), StandardCopyOption.REPLACE_EXISTING);
        long balance = wallet.balance(id);
        set(file, player(id) + ".rewards.2026_miner", null);

        WalletStore restored = open(file);
        assertTrue(restored.recoveredBackup(), "Loss of a positive receipt must lock payments");
        assertTrue(restored.hasReward(id, "2026_miner"));
        assertEquals(balance, restored.balance(id));
        assertEquals(WalletStore.RewardGrant.IO_ERROR,
                restored.creditQuestOnce(id, "2026_miner", 40));
        assertThrows(IOException.class, restored::save);
    }

    @Test
    void switchedOffReceiptCannotErasePreviousPayment() throws Exception {
        Path file = temp.resolve("players.yml");
        UUID id = UUID.randomUUID();
        WalletStore wallet = open(file);
        assertEquals(WalletStore.RewardGrant.OK, wallet.creditQuestOnce(id, "claim_xyz", 50));
        Files.copy(file, backup(file), StandardCopyOption.REPLACE_EXISTING);
        set(file, player(id) + ".rewards.claim_xyz", false);
        assertTrue(open(file).recoveredBackup(), "Changing true to false must lock recovery");
    }

    @Test
    void rewindingDailyWatermarkCausesReadOnlyRecovery() throws Exception {
        Path file = temp.resolve("players.yml");
        UUID id = UUID.randomUUID();
        WalletStore wallet = open(file);
        assertEquals(WalletStore.DailyStatus.OK, wallet.claimDailyAndSave(id).status());
        Files.copy(file, backup(file), StandardCopyOption.REPLACE_EXISTING);
        long day = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        set(file, player(id) + ".lastDaily", day - 1);

        WalletStore restored = open(file);
        assertTrue(restored.recoveredBackup(), "Daily history must never move backwards");
        assertEquals(WalletStore.DailyStatus.IO_ERROR, restored.claimDailyAndSave(id).status());
    }

    @Test
    void disappearingDailyHistoryOrExistingPlayerCausesRecovery() throws Exception {
        Path file = temp.resolve("players.yml");
        UUID id = UUID.randomUUID();
        WalletStore wallet = open(file);
        wallet.ensurePlayer(id);
        wallet.save();
        Files.copy(file, backup(file), StandardCopyOption.REPLACE_EXISTING);
        set(file, player(id) + ".lastDaily", null);
        assertTrue(open(file).recoveredBackup());
        Files.copy(backup(file), file, StandardCopyOption.REPLACE_EXISTING);
        set(file, player(id) + ".balance", null);
        assertTrue(open(file).recoveredBackup());
    }

    @Test
    void missingPrimaryWithBackupNeverInitializesFreshWallet() throws Exception {
        Path file = temp.resolve("players.yml");
        UUID id = UUID.randomUUID();
        WalletStore wallet = open(file);
        wallet.ensurePlayer(id);
        wallet.save();
        Files.copy(file, backup(file), StandardCopyOption.REPLACE_EXISTING);
        Files.delete(file);
        WalletStore restored = open(file);
        assertTrue(restored.recoveredBackup());
        assertEquals(100, restored.balance(id));
        assertEquals(WalletStore.Transfer.IO_ERROR,
                restored.transferAndSave(id, UUID.randomUUID(), 1));
        assertFalse(Files.exists(file), "Startup must not recreate lost primary");
    }

    @Test
    void malformedBackupCannotBeUsedToSilentlyAuthorizePayouts() throws Exception {
        Path file = temp.resolve("players.yml");
        UUID id = UUID.randomUUID();
        WalletStore wallet = open(file);
        wallet.ensurePlayer(id);
        wallet.save();
        Files.writeString(backup(file), "players: [broken YAML");
        assertThrows(IOException.class, () -> open(file));
    }
}
