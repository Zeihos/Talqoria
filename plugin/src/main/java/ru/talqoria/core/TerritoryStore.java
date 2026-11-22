package ru.talqoria.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Pure Java territory database. Use ONLY from the Paper main thread. */
public final class TerritoryStore {
    public enum Result {
        OK, INVALID_NAME, NAME_TAKEN, ALREADY_IN_TOWN, NOT_IN_TOWN, NOT_OWNER,
        ALREADY_CLAIMED, CLAIM_LIMIT, NOT_CLAIMED, FOREIGN_CLAIM, MEMBER_LIMIT,
        ALREADY_MEMBER, NOT_MEMBER, OWNER_CANNOT_LEAVE, ALREADY_OFFICER, NOT_OFFICER, ALREADY_GUEST, NOT_GUEST, SELF, ALREADY_CONTRIBUTED, XP_LIMIT, STORAGE_LOCKED
    }
    public record ChunkKey(UUID world, int x, int z) { }

    public static final class Town {
        private final UUID id;
        private final UUID owner;
        private String name;
        private long influence;
        private final Set<String> contributions = new HashSet<>();
        private final Set<UUID> members = new HashSet<>();
        private final Set<UUID> officers = new HashSet<>();
        private final Map<UUID, String> guests = new LinkedHashMap<>();
        private Town(UUID id, UUID owner, String name) {
            this.id = id; this.owner = owner; this.name = name; this.members.add(owner);
        }
        public UUID id() { return id; }
        public UUID owner() { return owner; }
        public String name() { return name; }
        public long influence() { return influence; }
        public int level() { return TerritoryStore.levelFor(influence); }
        public Set<UUID> members() { return Set.copyOf(members); }
        public Set<UUID> officers() { return Set.copyOf(officers); }
        public String role(UUID member) {
            if (owner.equals(member)) return "OWNER";
            if (officers.contains(member)) return "OFFICER";
            return members.contains(member) ? "MEMBER" : "NONE";
        }
        public Map<UUID, String> guests() { return Map.copyOf(guests); }
    }

    private final Path file;
    private final int maxClaims;
    private final int maxMembers;
    private final int maxGuests;
    private final Map<UUID, Town> towns = new LinkedHashMap<>();
    private final Map<ChunkKey, UUID> claims = new HashMap<>();
    private boolean recoveredBackup;
    private long revision;
    private String integrityDigest;

    public TerritoryStore(Path file, int maxClaims, int maxMembers, int maxGuests) throws IOException {
        this.file = file;
        this.maxClaims = Math.max(1, maxClaims);
        this.maxMembers = Math.max(1, maxMembers);
        this.maxGuests = Math.max(0, maxGuests);
        load();
    }

    public static int levelFor(long influence) {
        if (influence >= 1500) return 5;
        if (influence >= 800) return 4;
        if (influence >= 400) return 3;
        if (influence >= 150) return 2;
        return 1;
    }
    public int claimLimit(Town town) { return maxClaims + 2 * (town.level() - 1); }
    /** Contribution receipts make repeated quest completion safe even after a retry. */
    public Result contribute(UUID member, String receipt, long xp) {
        if (recoveredBackup) return Result.STORAGE_LOCKED;
        Town town = townFor(member);
        if (town == null) return Result.NOT_IN_TOWN;
        if (receipt == null || !receipt.matches("[a-zA-Z0-9_-]{1,120}") || xp <= 0 || xp > 1000)
            return Result.XP_LIMIT;
        if (town.contributions.contains(receipt)) return Result.ALREADY_CONTRIBUTED;
        if (town.contributions.size() >= 200_000 || Long.MAX_VALUE - town.influence < xp) return Result.XP_LIMIT;
        town.influence += xp;
        town.contributions.add(receipt);
        return Result.OK;
    }
    public boolean recoveredBackup() { return recoveredBackup; }
    private Path backupPath() { return file.resolveSibling(file.getFileName() + ".bak"); }

    /** Canonical integrity checksum of all snapshot properties except its own digest. */
    private static String checksum(Properties data) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            data.stringPropertyNames().stream()
                .filter(key -> !key.equals("integrity.sha256"))
                .sorted().forEach(key -> {
                    String value = data.getProperty(key);
                    byte[] bytes = (key.length() + ":" + key + value.length() + ":" + value)
                        .getBytes(StandardCharsets.UTF_8);
                    sha.update(bytes);
                });
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static long checkRevision(Properties data) throws IOException {
        final long revision;
        try { revision = Long.parseLong(data.getProperty("snapshot.revision", "0")); }
        catch (NumberFormatException malformed) { throw new IOException("Invalid territory revision", malformed); }
        if (revision < 0) throw new IOException("Negative territory revision");
        String supplied = data.getProperty("integrity.sha256");
        if (revision > 0 && supplied == null) throw new IOException("Sealed territory snapshot has no checksum");
        if (supplied != null && !supplied.equals(checksum(data)))
            throw new IOException("Territory snapshot checksum mismatch; protection must lock");
        return revision;
    }
    public Collection<Town> towns() { return List.copyOf(towns.values()); }
    public int claimCount(Town town) { return (int) claims.values().stream().filter(town.id::equals).count(); }
    public Town townFor(UUID player) {
        for (Town town : towns.values()) if (town.members.contains(player)) return town;
        return null;
    }
    public Town townAt(ChunkKey chunk) { return towns.get(claims.get(chunk)); }
    public boolean canBuild(UUID player, ChunkKey chunk) {
        if (recoveredBackup) return false;
        Town town = townAt(chunk);
        return town == null || town.members.contains(player) || town.guests.containsKey(player);
    }
    public boolean isProtected(ChunkKey chunk) { return claims.containsKey(chunk); }
    public boolean sameTerritory(ChunkKey from, ChunkKey to) {
        return java.util.Objects.equals(claims.get(from), claims.get(to));
    }
    public Result create(UUID owner, String inputName) {
        if (recoveredBackup) return Result.STORAGE_LOCKED;
        String name = normalizeName(inputName);
        if (name == null) return Result.INVALID_NAME;
        if (townFor(owner) != null) return Result.ALREADY_IN_TOWN;
        for (Town town : towns.values()) if (town.name.equalsIgnoreCase(name)) return Result.NAME_TAKEN;
        Town town = new Town(UUID.randomUUID(), owner, name);
        towns.put(town.id, town);
        return Result.OK;
    }
    public Result setOfficer(UUID actor, UUID target, boolean promote) {
        if (recoveredBackup) return Result.STORAGE_LOCKED;
        Town town = townFor(actor);
        if (town == null) return Result.NOT_IN_TOWN;
        if (!town.owner.equals(actor)) return Result.NOT_OWNER;
        if (target.equals(actor)) return Result.SELF;
        if (!town.members.contains(target)) return Result.NOT_MEMBER;
        if (promote) return town.officers.add(target) ? Result.OK : Result.ALREADY_OFFICER;
        return town.officers.remove(target) ? Result.OK : Result.NOT_OFFICER;
    }
    private static boolean canManage(Town town, UUID actor) {
        return town.owner.equals(actor) || town.officers.contains(actor);
    }
    public boolean mayInvite(UUID actor) {
        Town town = townFor(actor);
        return town != null && canManage(town, actor);
    }
    public Result addMember(UUID owner, UUID member) {
        if (recoveredBackup) return Result.STORAGE_LOCKED;
        Town town = townFor(owner);
        if (town == null) return Result.NOT_IN_TOWN;
        if (!canManage(town, owner)) return Result.NOT_OWNER;
        if (town.owner.equals(member)) return Result.SELF;
        if (townFor(member) != null) return Result.ALREADY_MEMBER;
        if (town.members.size() >= maxMembers) return Result.MEMBER_LIMIT;
        town.guests.remove(member);
        town.members.add(member);
        return Result.OK;
    }
    public Result leave(UUID member) {
        if (recoveredBackup) return Result.STORAGE_LOCKED;
        Town town = townFor(member);
        if (town == null) return Result.NOT_IN_TOWN;
        if (town.owner.equals(member)) return Result.OWNER_CANNOT_LEAVE;
        town.members.remove(member);
        town.officers.remove(member);
        return Result.OK;
    }
    public Result kick(UUID owner, UUID member) {
        if (recoveredBackup) return Result.STORAGE_LOCKED;
        Town town = townFor(owner);
        if (town == null) return Result.NOT_IN_TOWN;
        if (!canManage(town, owner)) return Result.NOT_OWNER;
        if (owner.equals(member)) return Result.SELF;
        if (town.owner.equals(member) || (town.officers.contains(member) && !town.owner.equals(owner))) return Result.NOT_OWNER;
        if (!town.members.remove(member)) return Result.NOT_MEMBER;
        town.officers.remove(member);
        return Result.OK;
    }
    public Result disband(UUID owner) {
        if (recoveredBackup) return Result.STORAGE_LOCKED;
        Town town = townFor(owner);
        if (town == null) return Result.NOT_IN_TOWN;
        if (!town.owner.equals(owner)) return Result.NOT_OWNER;
        towns.remove(town.id);
        claims.values().removeIf(town.id::equals);
        return Result.OK;
    }
    public Result claim(UUID owner, ChunkKey chunk) {
        if (recoveredBackup) return Result.STORAGE_LOCKED;
        Town town = townFor(owner);
        if (town == null) return Result.NOT_IN_TOWN;
        if (!canManage(town, owner)) return Result.NOT_OWNER;
        if (claims.containsKey(chunk)) return Result.ALREADY_CLAIMED;
        if (claimCount(town) >= claimLimit(town)) return Result.CLAIM_LIMIT;
        claims.put(chunk, town.id);
        return Result.OK;
    }
    public Result unclaim(UUID owner, ChunkKey chunk) {
        if (recoveredBackup) return Result.STORAGE_LOCKED;
        Town town = townFor(owner);
        if (town == null) return Result.NOT_IN_TOWN;
        if (!canManage(town, owner)) return Result.NOT_OWNER;
        UUID existing = claims.get(chunk);
        if (existing == null) return Result.NOT_CLAIMED;
        if (!town.id.equals(existing)) return Result.FOREIGN_CLAIM;
        claims.remove(chunk);
        return Result.OK;
    }
    public Result trust(UUID owner, UUID guest, String name) {
        if (recoveredBackup) return Result.STORAGE_LOCKED;
        Town town = townFor(owner);
        if (town == null) return Result.NOT_IN_TOWN;
        if (!canManage(town, owner)) return Result.NOT_OWNER;
        if (town.members.contains(guest) || guest.equals(owner)) return Result.ALREADY_MEMBER;
        if (town.guests.containsKey(guest)) return Result.ALREADY_GUEST;
        if (town.guests.size() >= maxGuests) return Result.MEMBER_LIMIT;
        town.guests.put(guest, name);
        return Result.OK;
    }
    public Result untrust(UUID owner, String playerName) {
        if (recoveredBackup) return Result.STORAGE_LOCKED;
        Town town = townFor(owner);
        if (town == null) return Result.NOT_IN_TOWN;
        if (!canManage(town, owner)) return Result.NOT_OWNER;
        UUID found = null;
        for (var entry : town.guests.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(playerName)) { found = entry.getKey(); break; }
        }
        if (found == null) return Result.NOT_GUEST;
        town.guests.remove(found);
        return Result.OK;
    }
    public List<ChunkKey> chunksFor(Town town) {
        List<ChunkKey> result = new ArrayList<>();
        for (var item : claims.entrySet()) if (item.getValue().equals(town.id)) result.add(item.getKey());
        return result;
    }
    private static String normalizeName(String value) {
        if (value == null) return null;
        String name = Normalizer.normalize(value.trim().replaceAll("\\s+", " "), Normalizer.Form.NFC);
        if (name.length() < 3 || name.length() > 24 ||
            !name.matches("[\\p{L}0-9][\\p{L}0-9 _-]*")) return null;
        return name;
    }
    private void load() throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        if (!Files.exists(file) && !Files.exists(backup)) return; // genuinely new world
        if (!Files.exists(file)) {
            try { read(backup); recoveredBackup = true; return; }
            catch (Exception invalid) {
                towns.clear(); claims.clear();
                throw new IOException("Territory primary missing and backup invalid; recovery required", invalid);
            }
        }
        try {
            read(file);
            if (Files.exists(backup)) {
                // A fully parsed backup is required for trustworthy recovery. Do not
                // silently accept an invalid backup while claim protection is active.
                TerritoryStore old = new TerritoryStore(backup, maxClaims, maxMembers, maxGuests);
                if (old.recoveredBackup) throw new IOException("Nested recovery in territory backup");
                if (revision < old.revision ||
                        (revision > 0 && revision == old.revision &&
                         !java.util.Objects.equals(integrityDigest, old.integrityDigest)))
                    throw new IOException("Territory primary is older than backup or has diverged at same revision");
            }
        } catch (Exception primary) {
            towns.clear(); claims.clear();
            if (!Files.exists(backup)) throw new IOException("Territory data corrupt, no backup: " + primary.getMessage(), primary);
            try { read(backup); recoveredBackup = true; }
            catch (Exception secondary) {
                towns.clear(); claims.clear();
                throw new IOException("Territory data AND backup corrupt: " + secondary.getMessage(), secondary);
            }
        }
    }
    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null) throw new IOException("Missing " + key);
        return value;
    }
    private void read(Path source) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(source)) { p.loadFromXML(in); }
        if (!"1".equals(required(p, "format"))) throw new IOException("Unsupported territory version");
        long loadedRevision = checkRevision(p);
        int townCount = Integer.parseInt(required(p, "towns.count"));
        int claimCount = Integer.parseInt(required(p, "claims.count"));
        if (townCount < 0 || townCount > 100_000 || claimCount < 0 || claimCount > 2_000_000)
            throw new IOException("Invalid counts");
        Set<UUID> allMembers = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < townCount; i++) {
            String prefix = "town." + i + ".";
            UUID id = UUID.fromString(required(p, prefix + "id"));
            UUID owner = UUID.fromString(required(p, prefix + "owner"));
            String name = normalizeName(required(p, prefix + "name"));
            if (name == null || !names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Invalid or duplicate name");
            Town town = new Town(id, owner, name);
            // Optional fields keep v0.3 territory files backwards compatible.
            town.influence = Long.parseLong(p.getProperty(prefix + "influence", "0"));
            if (town.influence < 0) throw new IOException("Invalid town influence");
            for (String receipt : p.getProperty(prefix + "contributions", "").split(",")) {
                if (!receipt.isBlank()) {
                    if (!receipt.matches("[a-zA-Z0-9_-]{1,120}") || !town.contributions.add(receipt))
                        throw new IOException("Invalid contribution receipt");
                }
            }
            if (town.contributions.size() > 200_000) throw new IOException("Excessive contributions");
            for (String uuid : required(p, prefix + "members").split(",")) {
                if (!uuid.isBlank()) town.members.add(UUID.fromString(uuid));
            }
            // v0.3-v0.5 saves have no officers field.
            for (String uuid : p.getProperty(prefix + "officers", "").split(",")) {
                if (!uuid.isBlank()) {
                    UUID officer = UUID.fromString(uuid);
                    if (!town.officers.add(officer)) throw new IOException("Duplicate officer");
                }
            }
            if (!town.members.containsAll(town.officers) || town.officers.contains(owner))
                throw new IOException("Officers must be ordinary members, not owners");
            for (String uuid : required(p, prefix + "guests").split(",")) {
                if (!uuid.isBlank()) {
                    UUID guest = UUID.fromString(uuid);
                    String guestName = required(p, prefix + "guest." + guest);
                    if (!guestName.matches("[A-Za-z0-9_]{1,16}")) throw new IOException("Invalid guest name");
                    town.guests.put(guest, guestName);
                }
            }
            if (town.members.size() > maxMembers || town.guests.size() > maxGuests ||
                    !java.util.Collections.disjoint(town.members, town.guests.keySet()) ||
                    towns.putIfAbsent(id, town) != null) throw new IOException("Invalid town membership");
            for (UUID member : town.members) if (!allMembers.add(member)) throw new IOException("Member belongs to multiple towns");
        }
        for (int i = 0; i < claimCount; i++) {
            String prefix = "claim." + i + ".";
            ChunkKey key = new ChunkKey(UUID.fromString(required(p, prefix + "world")),
                Integer.parseInt(required(p, prefix + "x")), Integer.parseInt(required(p, prefix + "z")));
            UUID town = UUID.fromString(required(p, prefix + "town"));
            if (!towns.containsKey(town) || claims.putIfAbsent(key, town) != null)
                throw new IOException("Invalid or duplicate claim");
        }
        // Existing claims remain protected if an operator reduces configured caps.
        // The new cap is only enforced for future claims.
        revision = loadedRevision;
        integrityDigest = checksum(p);
    }
    public void save() throws IOException {
        if (recoveredBackup) throw new IOException("Territory store recovered from backup; manual reconciliation required");
        if (revision == Long.MAX_VALUE) throw new IOException("Territory snapshot revision exhausted");
        Properties p = new Properties();
        p.setProperty("format", "1");
        p.setProperty("towns.count", Integer.toString(towns.size()));
        int i = 0;
        for (Town town : towns.values()) {
            String prefix = "town." + i++ + ".";
            p.setProperty(prefix + "id", town.id.toString());
            p.setProperty(prefix + "owner", town.owner.toString());
            p.setProperty(prefix + "name", town.name);
            p.setProperty(prefix + "influence", Long.toString(town.influence));
            p.setProperty(prefix + "contributions", String.join(",", town.contributions));
            p.setProperty(prefix + "members", String.join(",", town.members.stream().map(UUID::toString).toList()));
            p.setProperty(prefix + "officers", String.join(",", town.officers.stream().map(UUID::toString).toList()));
            p.setProperty(prefix + "guests", String.join(",", town.guests.keySet().stream().map(UUID::toString).toList()));
            for (var guest : town.guests.entrySet()) p.setProperty(prefix + "guest." + guest.getKey(), guest.getValue());
        }
        p.setProperty("claims.count", Integer.toString(claims.size()));
        i = 0;
        for (var claim : claims.entrySet()) {
            String prefix = "claim." + i++ + ".";
            p.setProperty(prefix + "world", claim.getKey().world().toString());
            p.setProperty(prefix + "x", Integer.toString(claim.getKey().x()));
            p.setProperty(prefix + "z", Integer.toString(claim.getKey().z()));
            p.setProperty(prefix + "town", claim.getValue().toString());
        }
        long nextRevision = revision + 1;
        p.setProperty("snapshot.revision", Long.toString(nextRevision));
        String seal = checksum(p);
        p.setProperty("integrity.sha256", seal);
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "territories-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) { p.storeToXML(out, "TALQORIA territories v1", "UTF-8"); }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            // Never truncate an existing .bak while copying. Stage the old primary,
            // force it to disk and replace the backup atomically when supported.
            if (Files.exists(file)) {
                Path backupTemp = Files.createTempFile(parent, "territories-backup-", ".tmp");
                try {
                    Files.copy(file, backupTemp, StandardCopyOption.REPLACE_EXISTING);
                    try (FileChannel channel = FileChannel.open(backupTemp, StandardOpenOption.WRITE)) {
                        channel.force(true);
                    }
                    try { Files.move(backupTemp, backupPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException e) {
                        Files.move(backupTemp, backupPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally { Files.deleteIfExists(backupTemp); }
            }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            revision = nextRevision;
            integrityDigest = seal;
        } finally { Files.deleteIfExists(temp); }
    }
}
