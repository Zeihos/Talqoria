package ru.talqoria.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;

/** Small local test store; methods must be called on the Paper main thread. */
public final class WalletStore {
    public enum Transfer { OK, INSUFFICIENT, INVALID, OVERFLOW, IO_ERROR }
    public enum DailyStatus { OK, ALREADY, OVERFLOW, IO_ERROR }
    public record DailyResult(DailyStatus status, long amount) {}
    public enum RewardGrant { OK, ALREADY, OVERFLOW, INVALID, IO_ERROR }
    public enum MarketChange { OK, INSUFFICIENT, OVERFLOW, INVALID, IO_ERROR }
    private final Path file;
    private final YamlConfiguration data;
    private final long startingBalance;
    private final long dailyReward;
    private final long maxTransfer;
    private boolean recoveredBackup;

    public WalletStore(Path file, long startingBalance, long dailyReward, long maxTransfer) throws IOException {
        this.file = file;
        this.startingBalance = Math.max(0, startingBalance);
        this.dailyReward = Math.max(0, dailyReward);
        this.maxTransfer = Math.max(1, maxTransfer);
        this.data = new YamlConfiguration();
        Path backup = backupPath();
        if (!Files.exists(file) && Files.exists(backup)) {
            // A missing primary with an existing backup is NOT a fresh server. Never reset wallets.
            recoverBackup(new IOException("Primary wallet file missing while backup exists"));
        } else if (Files.exists(file)) {
            try {
                data.load(file.toFile());
                validate();
                validateAgainstBackup(backup);
            } catch (Exception invalid) {
                recoverBackup(invalid);
            }
        }
    }

    /** Recovered data is intentionally READ ONLY until a human reconciles the primary and backup. */
    public boolean recoveredBackup() { return recoveredBackup; }

    private Path backupPath() { return file.resolveSibling(file.getFileName() + ".bak"); }

    private void recoverBackup(Exception primaryError) throws IOException {
        Path backup = backupPath();
        if (!Files.isRegularFile(backup))
            throw new IOException("Wallet primary invalid/missing and no usable backup: " + primaryError.getMessage(), primaryError);
        try {
            data.load(backup.toFile());
            validate();
            recoveredBackup = true;
        } catch (Exception backupError) {
            IOException failed = new IOException("Both wallet primary and backup are invalid: " + backupError.getMessage(), backupError);
            failed.addSuppressed(primaryError);
            throw failed;
        }
    }

    /**
     * A valid primary snapshot may contain newer balances, payouts and players, but it
     * must never forget proof of an already-claimed reward or move daily timestamps
     * backwards compared with the previous durable backup. Otherwise an operator
     * restoring a stale/corrupted file could trigger duplicate grants after restart.
     */
    private void validateAgainstBackup(Path backup) throws IOException, InvalidConfigurationException {
        if (!Files.exists(backup)) return;
        YamlConfiguration previous = new YamlConfiguration();
        previous.load(backup.toFile());
        validate(previous);
        for (String key : previous.getKeys(true)) {
            if (key.matches("players\\.[^.]+\\.balance") && !data.contains(key))
                throw new IOException("Primary snapshot is missing player " + key + " present in backup");
            if (key.matches("players\\.[^.]+\\.lastDaily")) {
                Number oldDay = (Number) previous.get(key);
                Object newDay = data.get(key);
                if (!(newDay instanceof Number currentDay) || currentDay.longValue() < oldDay.longValue())
                    throw new IOException("Daily reward watermark moved backwards or disappeared: " + key);
            }
            if (key.matches("players\\.[^.]+\\.rewards\\.[a-zA-Z0-9_-]{1,120}") &&
                    Boolean.TRUE.equals(previous.get(key)) && !Boolean.TRUE.equals(data.get(key)))
                throw new IOException("Paid reward receipt is missing in primary: " + key);
        }
    }

    /** Validate primary and backup equally strictly before comparing receipts. */
    private void validate() throws IOException { validate(data); }

    private static void validate(YamlConfiguration snapshot) throws IOException {
        java.util.Set<String> playerIds = new java.util.HashSet<>();
        for (String path : snapshot.getKeys(true)) {
            if (!path.startsWith("players.")) continue;
            String[] parts = path.split("\\.");
            if (parts.length < 3) continue; // section names in Bukkit's real YamlConfiguration
            try { UUID.fromString(parts[1]); }
            catch (IllegalArgumentException invalid) { throw new IOException("Invalid wallet UUID in " + path, invalid); }
            playerIds.add(parts[1]);
            Object value = snapshot.get(path);
            if (parts.length == 3 && (parts[2].equals("balance") || parts[2].equals("lastDaily"))) {
                if (!(value instanceof Number n) || n.doubleValue() != n.longValue()
                        || (parts[2].equals("balance") && n.longValue() < 0)
                        || (parts[2].equals("lastDaily") && n.longValue() < -1))
                    throw new IOException("Invalid wallet numeric field: " + path);
            } else if (parts.length == 4 && parts[2].equals("rewards")) {
                if (!parts[3].matches("[a-zA-Z0-9_-]{1,120}") || !(value instanceof Boolean))
                    throw new IOException("Invalid wallet receipt: " + path);
            } else if (!(parts.length == 3 && parts[2].equals("rewards"))) {
                throw new IOException("Unknown wallet field: " + path);
            }
        }
        for (String id : playerIds) {
            if (!snapshot.contains("players." + id + ".balance"))
                throw new IOException("Missing player balance for " + id);
        }
    }

    private String key(UUID player) { return "players." + player; }

    public boolean ensurePlayer(UUID id) {
        String key = key(id);
        if (recoveredBackup || data.contains(key + ".balance")) return false;
        data.set(key + ".balance", startingBalance);
        data.set(key + ".lastDaily", -1L);
        return true;
    }

    public long balance(UUID id) {
        ensurePlayer(id);
        return Math.max(0L, data.getLong(key(id) + ".balance"));
    }

    /** One wallet snapshot for both players: persist before confirming a transfer. */
    public Transfer transferAndSave(UUID source, UUID target, long amount) {
        if (source == null || target == null || source.equals(target) || amount <= 0 || amount > maxTransfer)
            return Transfer.INVALID;
        if (recoveredBackup) return Transfer.IO_ERROR;
        String snapshot = data.saveToString();
        long from = balance(source), to = balance(target);
        if (from < amount) return Transfer.INSUFFICIENT;
        if (Long.MAX_VALUE - to < amount) return Transfer.OVERFLOW;
        data.set(key(source) + ".balance", from - amount);
        data.set(key(target) + ".balance", to + amount);
        try { save(); return Transfer.OK; }
        catch (IOException failed) { rollback(snapshot); return Transfer.IO_ERROR; }
    }

    /** Daily reward and last-claim day are saved together; never confirm an unpersisted grant. */
    public DailyResult claimDailyAndSave(UUID id) {
        if (id == null || recoveredBackup) return new DailyResult(DailyStatus.IO_ERROR, 0);
        String snapshot = data.saveToString();
        ensurePlayer(id);
        long day = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        if (data.getLong(key(id) + ".lastDaily", -1L) >= day)
            return new DailyResult(DailyStatus.ALREADY, 0);
        long before = balance(id);
        if (Long.MAX_VALUE - before < dailyReward)
            return new DailyResult(DailyStatus.OVERFLOW, 0);
        data.set(key(id) + ".balance", before + dailyReward);
        data.set(key(id) + ".lastDaily", day);
        try { save(); return new DailyResult(DailyStatus.OK, dailyReward); }
        catch (IOException failed) { rollback(snapshot); return new DailyResult(DailyStatus.IO_ERROR, 0); }
    }

    /** Restore the whole in-memory snapshot after a failed write, including newly created players. */
    private void rollback(String snapshot) {
        try { data.loadFromString(snapshot); }
        catch (InvalidConfigurationException impossible) {
            throw new IllegalStateException("Cannot restore local wallet snapshot; stop server", impossible);
        }
    }

    /** Read-only idempotency query; reward receipts live in the same wallet snapshot. */
    public boolean hasReward(UUID player, String receipt) {
        if (receipt == null || !receipt.matches("[a-zA-Z0-9_-]{1,120}")) return false;
        return data.getBoolean(key(player) + ".rewards." + receipt, false);
    }

    /** Shop debit (negative) / credit (positive), saved before physical item delivery. */
    public MarketChange marketChange(UUID player, long delta) {
        if (delta == 0 || delta == Long.MIN_VALUE || Math.abs(delta) > maxTransfer)
            return MarketChange.INVALID;
        if (recoveredBackup) return MarketChange.IO_ERROR;
        String snapshot = data.saveToString();
        long before = balance(player);
        if (delta < 0 && before < -delta) return MarketChange.INSUFFICIENT;
        if (delta > 0 && Long.MAX_VALUE - before < delta) return MarketChange.OVERFLOW;
        data.set(key(player) + ".balance", before + delta);
        try { save(); return MarketChange.OK; }
        catch (IOException failed) {
            rollback(snapshot);
            return MarketChange.IO_ERROR;
        }
    }

    /** Persisted one-time receipt. Retrying the same daily quest cannot pay twice. */
    public RewardGrant creditQuestOnce(UUID player, String receipt, long amount) {
        if (receipt == null || !receipt.matches("[a-zA-Z0-9_-]{1,120}") || amount <= 0 || amount > 1000)
            return RewardGrant.INVALID;
        if (recoveredBackup) return RewardGrant.IO_ERROR;
        String snapshot = data.saveToString();
        long before = balance(player);
        String key = key(player) + ".rewards." + receipt;
        if (data.getBoolean(key, false)) return RewardGrant.ALREADY;
        if (Long.MAX_VALUE - before < amount) return RewardGrant.OVERFLOW;
        data.set(key(player) + ".balance", before + amount);
        data.set(key, true);
        try { save(); return RewardGrant.OK; }
        catch (IOException failed) {
            // Discard the mutation so an operator can fix disk errors and retry.
            rollback(snapshot);
            return RewardGrant.IO_ERROR;
        }
    }

    public void save() throws IOException {
        if (recoveredBackup) throw new IOException("Recovered wallet snapshot is read-only; stop server and reconcile files manually");
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "players-", ".tmp");
        try {
            Files.writeString(temp, data.saveToString(), StandardCharsets.UTF_8);
            // Request durable persistence of the new snapshot before replacing the previous one.
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (Files.exists(file)) {
                // Do not truncate a previously valid .bak during an interrupted copy.
                Path backupTemp = Files.createTempFile(file.getParent(), "players-backup-", ".tmp");
                try {
                    Files.copy(file, backupTemp, StandardCopyOption.REPLACE_EXISTING);
                    try (FileChannel channel = FileChannel.open(backupTemp, StandardOpenOption.WRITE)) {
                        channel.force(true);
                    }
                    try {
                        Files.move(backupTemp, backupPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        Files.move(backupTemp, backupPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally { Files.deleteIfExists(backupTemp); }
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
