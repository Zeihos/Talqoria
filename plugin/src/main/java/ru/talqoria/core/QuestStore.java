package ru.talqoria.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Local daily quests. Pure Java logic; invoke only on the Minecraft main thread. */
public final class QuestStore {
    public enum Objective { STONE, LOGS, ZOMBIES }
    public enum ClaimResult { READY, INCOMPLETE, ALREADY_CLAIMED, UNKNOWN }
    public record Quest(String id, String title, Objective objective, int target, long coins, long townXp) { }
    public record Status(Quest quest, int progress, boolean claimed) { }
    public static final List<Quest> QUESTS = List.of(
            new Quest("miner", "Каменотёс", Objective.STONE, 48, 40, 35),
            new Quest("lumber", "Лесоруб", Objective.LOGS, 24, 50, 45),
            new Quest("hunter", "Защитник", Objective.ZOMBIES, 8, 70, 60));
    private static final int MAX_PLAYERS = 250_000;

    private static final class PlayerProgress {
        long day;
        final Map<String, Integer> progress = new HashMap<>();
        final java.util.Set<String> claimed = new java.util.HashSet<>();
        PlayerProgress(long day) { this.day = day; }
    }
    private final Path file;
    private final LongSupplier daySource;
    private final Map<UUID, PlayerProgress> players = new HashMap<>();
    private boolean recoveredBackup;

    public QuestStore(Path file) throws IOException {
        this(file, () -> LocalDate.now(ZoneOffset.UTC).toEpochDay());
    }
    QuestStore(Path file, LongSupplier daySource) throws IOException {
        this.file = file;
        this.daySource = daySource;
        load();
    }
    public boolean recoveredBackup() { return recoveredBackup; }
    private Path backupPath() { return file.resolveSibling(file.getFileName() + ".bak"); }
    public long today() { return daySource.getAsLong(); }
    public String receipt(UUID player, String questId) { return today() + "_" + questId + "_" + player; }
    private PlayerProgress state(UUID player) {
        PlayerProgress p = players.computeIfAbsent(player, x -> new PlayerProgress(today()));
        if (p.day != today()) {
            p.day = today();
            p.progress.clear();
            p.claimed.clear();
        }
        return p;
    }
    public List<Status> statuses(UUID player) {
        PlayerProgress p = state(player);
        List<Status> result = new ArrayList<>();
        for (Quest q : QUESTS) result.add(new Status(q, p.progress.getOrDefault(q.id(), 0), p.claimed.contains(q.id())));
        return List.copyOf(result);
    }
    /** True if a progress value changed; never exceeds its configured target. */
    public boolean record(UUID player, Objective objective) {
        if (recoveredBackup) return false; // recovered data is read-only
        PlayerProgress p = state(player);
        boolean changed = false;
        for (Quest q : QUESTS) {
            if (q.objective() != objective || p.claimed.contains(q.id())) continue;
            int before = p.progress.getOrDefault(q.id(), 0);
            if (before < q.target()) { p.progress.put(q.id(), before + 1); changed = true; }
        }
        return changed;
    }
    public Quest find(String id) {
        for (Quest q : QUESTS) if (q.id().equalsIgnoreCase(id)) return q;
        return null;
    }
    public ClaimResult canClaim(UUID player, String questId) {
        Quest q = find(questId);
        if (q == null) return ClaimResult.UNKNOWN;
        PlayerProgress p = state(player);
        if (p.claimed.contains(q.id())) return ClaimResult.ALREADY_CLAIMED;
        if (p.progress.getOrDefault(q.id(), 0) < q.target()) return ClaimResult.INCOMPLETE;
        return ClaimResult.READY;
    }
    /** Caller must first persist the idempotent wallet receipt (or verify it exists). */
    public boolean markClaimed(UUID player, String id) {
        if (recoveredBackup) return false; // never change recovered claim history
        if (canClaim(player, id) != ClaimResult.READY) return false;
        return state(player).claimed.add(find(id).id());
    }
    public void save() throws IOException {
        if (recoveredBackup) throw new IOException("Quest store recovered from backup; manual reconciliation required");
        Properties p = new Properties();
        p.setProperty("format", "1");
        p.setProperty("players.count", String.valueOf(players.size()));
        int i = 0;
        for (var entry : players.entrySet()) {
            String prefix = "player." + i++ + ".";
            PlayerProgress value = entry.getValue();
            p.setProperty(prefix + "uuid", entry.getKey().toString());
            p.setProperty(prefix + "day", Long.toString(value.day));
            for (Quest quest : QUESTS) {
                p.setProperty(prefix + quest.id() + ".progress", Integer.toString(value.progress.getOrDefault(quest.id(), 0)));
                p.setProperty(prefix + quest.id() + ".claimed", Boolean.toString(value.claimed.contains(quest.id())));
            }
        }
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "quests-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) { p.storeToXML(out, "TALQORIA quests v1", "UTF-8"); }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            // Avoid truncating a good .bak on an interrupted backup copy. Keep the
            // previous snapshot until the new one is complete and durable.
            if (Files.exists(file)) {
                Path backupTemp = Files.createTempFile(parent, "quests-backup-", ".tmp");
                try {
                    Files.copy(file, backupTemp, StandardCopyOption.REPLACE_EXISTING);
                    try (FileChannel channel = FileChannel.open(backupTemp, StandardOpenOption.WRITE)) {
                        channel.force(true);
                    }
                    try { Files.move(backupTemp, backupPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException e) {
                        Files.move(backupTemp, backupPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally { Files.deleteIfExists(backupTemp); }
            }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    /**
     * A syntactically valid but older primary snapshot is not necessarily safe:
     * it could silently forget a completed task, a paid claim or an entire player.
     * When a backup exists, compare it before allowing any quest changes.
     *
     * A newer UTC day resets daily progress legitimately, so progress is only
     * compared when both snapshots describe the same day.
     */
    private static void verifyAgainstBackup(Map<UUID, PlayerProgress> primary,
                                            Map<UUID, PlayerProgress> previous) throws IOException {
        for (var entry : previous.entrySet()) {
            UUID player = entry.getKey();
            PlayerProgress old = entry.getValue();
            PlayerProgress current = primary.get(player);
            if (current == null)
                throw new IOException("Quest primary missing player preserved in backup: " + player);
            if (current.day < old.day)
                throw new IOException("Quest day moved backwards for player " + player);
            if (current.day == old.day) {
                for (Quest quest : QUESTS) {
                    String id = quest.id();
                    if (current.progress.getOrDefault(id, 0) < old.progress.getOrDefault(id, 0))
                        throw new IOException("Quest progress moved backwards for player " + player + " quest " + id);
                    if (old.claimed.contains(id) && !current.claimed.contains(id))
                        throw new IOException("Quest claim disappeared for player " + player + " quest " + id);
                }
            }
        }
    }
    private void load() throws IOException {
        Path backup = backupPath();
        if (!Files.exists(file) && !Files.exists(backup)) return; // new world
        if (!Files.exists(file)) {
            try {
                players.putAll(read(backup));
                recoveredBackup = true;
                return;
            } catch (Exception invalid) {
                players.clear();
                throw new IOException("Quest primary missing and backup invalid; manual recovery required", invalid);
            }
        }
        try {
            Map<UUID, PlayerProgress> current = read(file);
            if (Files.exists(backup)) verifyAgainstBackup(current, read(backup));
            players.putAll(current);
        } catch (Exception primaryError) {
            players.clear();
            if (!Files.exists(backup))
                throw new IOException("Quest primary invalid and no backup; manual recovery required", primaryError);
            try {
                players.putAll(read(backup));
                recoveredBackup = true; // fail closed: available for inspection, not for editing
            } catch (Exception backupError) {
                players.clear();
                IOException failure = new IOException("Quest primary/backup mismatch and backup invalid", backupError);
                failure.addSuppressed(primaryError);
                throw failure;
            }
        }
    }
    private static Map<UUID, PlayerProgress> read(Path source) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(source)) { p.loadFromXML(in); }
        if (!"1".equals(p.getProperty("format"))) throw new IOException("Unsupported quest data version");
        final int count;
        try { count = Integer.parseInt(required(p, "players.count")); }
        catch (NumberFormatException invalid) { throw new IOException("Invalid quest player count", invalid); }
        if (count < 0 || count > MAX_PLAYERS) throw new IOException("Invalid player count");
        Map<UUID, PlayerProgress> loaded = new HashMap<>();
        try {
            for (int i = 0; i < count; i++) {
                String prefix = "player." + i + ".";
                UUID id = UUID.fromString(required(p, prefix + "uuid"));
                long day = Long.parseLong(required(p, prefix + "day"));
                PlayerProgress value = new PlayerProgress(day);
                for (Quest quest : QUESTS) {
                    int progress = Integer.parseInt(required(p, prefix + quest.id() + ".progress"));
                    if (progress < 0 || progress > quest.target()) throw new IOException("Invalid quest progress");
                    value.progress.put(quest.id(), progress);
                    String claimed = required(p, prefix + quest.id() + ".claimed");
                    if (!claimed.equals("true") && !claimed.equals("false"))
                        throw new IOException("Invalid claim flag");
                    if (claimed.equals("true")) {
                        if (progress != quest.target()) throw new IOException("Claim before completion");
                        value.claimed.add(quest.id());
                    }
                }
                if (loaded.putIfAbsent(id, value) != null) throw new IOException("Duplicate player");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid quest snapshot record", invalid);
        }
        return loaded;
    }
    private static String required(Properties p, String name) throws IOException {
        String value = p.getProperty(name);
        if (value == null) throw new IOException("Missing " + name);
        return value;
    }
}
