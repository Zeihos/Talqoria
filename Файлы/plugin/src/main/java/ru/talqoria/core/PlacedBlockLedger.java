package ru.talqoria.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Conservative anti-farm provenance ledger. Stores block positions, NOT a natural block world database. */
public final class PlacedBlockLedger {
    public record Position(UUID world, int x, int y, int z) { }
    private static final int MAX = 120_000;
    private final Path file;
    private final Set<Position> placed = new HashSet<>();
    public PlacedBlockLedger(Path file) throws IOException { this.file = file; load(); }
    public int size() { return placed.size(); }
    public boolean contains(Position pos) { return placed.contains(pos); }
    public void record(Position pos) throws IOException {
        if (placed.contains(pos)) return;
        if (placed.size() >= MAX) throw new IOException("Provenance ledger full; quest mining must lock");
        placed.add(pos);
        try { save(); }
        catch (IOException e) { placed.remove(pos); throw e; }
    }
    /** Returns true if the block was player-placed and should NOT count for quest progress. */
    public boolean consume(Position pos) throws IOException {
        if (!placed.remove(pos)) return false;
        try { save(); }
        catch (IOException e) { placed.add(pos); throw e; }
        return true;
    }
    /** Moves provenance when pistons relocate tracked blocks. */
    public void translate(java.util.Collection<Position> origins, int dx, int dy, int dz) throws IOException {
        Set<Position> moving = new HashSet<>();
        for (Position pos : origins) if (placed.contains(pos)) moving.add(pos);
        if (moving.isEmpty()) return;
        Set<Position> old = new HashSet<>(placed);
        placed.removeAll(moving);
        for (Position p : moving) placed.add(new Position(p.world, p.x + dx, p.y + dy, p.z + dz));
        try { save(); }
        catch (IOException e) { placed.clear(); placed.addAll(old); throw e; }
    }
    private void save() throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "block-ledger-", ".tmp");
        try {
            Properties data = new Properties();
            data.setProperty("format", "1");
            data.setProperty("count", Integer.toString(placed.size()));
            int i=0;
            for (Position p : placed) data.setProperty("p." + i++, p.world + "," + p.x + "," + p.y + "," + p.z);
            try (OutputStream out = Files.newOutputStream(temp)) { data.storeToXML(out, "TALQORIA placed blocks"); }
            try (var channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private void load() throws IOException {
        if (!Files.exists(file)) return;
        Properties data = new Properties();
        try (InputStream in = Files.newInputStream(file)) { data.loadFromXML(in); }
        try {
            if (!"1".equals(data.getProperty("format"))) throw new IllegalArgumentException("format");
            int count = Integer.parseInt(data.getProperty("count"));
            if (count < 0 || count > MAX) throw new IllegalArgumentException("count");
            for (int i=0;i<count;i++) {
                String[] parts = data.getProperty("p." + i).split(",", -1);
                if (parts.length != 4 || !placed.add(new Position(UUID.fromString(parts[0]),
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]))))
                    throw new IllegalArgumentException("entry/duplicate");
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid placed-block ledger; no quest mining until repaired", invalid);
        }
    }
}
