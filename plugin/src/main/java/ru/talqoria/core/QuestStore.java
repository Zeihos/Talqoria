package ru.talqoria.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
            // The old primary is backed up before replacement, so it never silently disappears.
            if (Files.exists(file)) Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private void load() throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        if (!Files.exists(file) && !Files.exists(backup)) return; // genuinely new world
        if (!Files.exists(file)) {
            try { read(backup); recoveredBackup = true; return; }
            catch (Exception invalid) {
                players.clear();
                throw new IOException("Quest primary missing and backup invalid; recovery required", invalid);
            }
        }
        try { read(file); }
        catch (Exception first) {
            players.clear();
            if (!Files.exists(backup)) throw new IOException("Quest data invalid and no backup", first);
            try { read(backup); recoveredBackup = true; }
            catch (Exception second) {
                players.clear();
                throw new IOException("Quest data AND backup invalid", second);
            }
        }
    }
    private void read(Path source) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(source)) { p.loadFromXML(in); }
        if (!"1".equals(p.getProperty("format"))) throw new IOException("Unsupported quest data version");
        int count = Integer.parseInt(required(p, "players.count"));
        if (count < 0 || count > MAX_PLAYERS) throw new IOException("Invalid player count");
        for (int i = 0; i < count; i++) {
            String prefix = "player." + i + ".";
            UUID id = UUID.fromString(required(p, prefix + "uuid"));
            long day = Long.parseLong(required(p, prefix + "day"));
            PlayerProgress value = new PlayerProgress(day);
            for (Quest q : QUESTS) {
                int progress = Integer.parseInt(required(p, prefix + q.id() + ".progress"));
                if (progress < 0 || progress > q.target()) throw new IOException("Invalid quest progress");
                value.progress.put(q.id(), progress);
                String claimed = required(p, prefix + q.id() + ".claimed");
                if (!claimed.equals("true") && !claimed.equals("false")) throw new IOException("Invalid claim flag");
                if (claimed.equals("true")) {
                    if (progress != q.target()) throw new IOException("Claim before completion");
                    value.claimed.add(q.id());
                }
            }
            if (players.putIfAbsent(id, value) != null) throw new IOException("Duplicate player");
        }
    }
    private static String required(Properties p, String name) throws IOException {
        String value = p.getProperty(name);
        if (value == null) throw new IOException("Missing " + name);
        return value;
    }
}
