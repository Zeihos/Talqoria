package ru.talqoria.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32C;

/**
 * Anti-farm provenance ledger. Replays an append-only journal instead of serialising
 * the entire XML database on every block event. Every acknowledged event is forced to disk.
 * An incomplete/corrupted journal fails closed; only a clean stop checkpoints the XML snapshot.
 */
public final class PlacedBlockLedger {
    public record Position(UUID world, int x, int y, int z) { }
    private static final int MAX = 120_000;
    private static final long MAX_JOURNAL_BYTES = 128L * 1024L * 1024L;
    private final Path file;
    private final Path journal;
    private final Set<Position> placed = new HashSet<>();
    private boolean locked;

    public PlacedBlockLedger(Path file) throws IOException {
        this.file = file;
        this.journal = file.resolveSibling("placed-blocks.journal");
        if (!Files.exists(file) && Files.exists(backupPath()))
            throw new IOException("Placed-block primary missing but .bak exists; stop and reconcile the saved data");
        loadSnapshot();
        replayJournal();
    }
    private Path backupPath() { return file.resolveSibling(file.getFileName() + ".bak"); }
    public int size() { return placed.size(); }
    public boolean contains(Position pos) { return placed.contains(pos); }
    public boolean isLocked() { return locked; }

    public void record(Position pos) throws IOException {
        assertWritable();
        if (placed.contains(pos)) return;
        if (placed.size() >= MAX) throw new IOException("Provenance ledger full; quest mining must lock");
        appendChanges(List.of("+" + encode(pos)));
        placed.add(pos);
    }
    /** Returns true if the block was player-placed and must not count for quest progress. */
    public boolean consume(Position pos) throws IOException {
        assertWritable();
        if (!placed.contains(pos)) return false;
        appendChanges(List.of("-" + encode(pos)));
        placed.remove(pos);
        return true;
    }
    /**
     * Atomically consume every player-placed block destroyed by a single explosion.
     * Affected blocks are deduplicated, and one durable journal record replaces
     * N individual fsyncs on the Paper server thread. An I/O failure leaves the
     * in-memory set unchanged and locks subsequent writes for reconciliation.
     * @return count of tracked player-placed blocks consumed
     */
    public int consumeAll(Collection<Position> positions) throws IOException {
        assertWritable();
        Set<Position> removed = new java.util.LinkedHashSet<>();
        for (Position position : positions) {
            if (position == null) throw new IOException("Null provenance position");
            if (placed.contains(position)) removed.add(position);
        }
        if (removed.isEmpty()) return 0;
        List<String> operations = new ArrayList<>(removed.size());
        for (Position position : removed) operations.add("-" + encode(position));
        appendChanges(operations);
        placed.removeAll(removed);
        return removed.size();
    }

    /** Atomic multi-block provenance move: one durable journal transaction per piston event. */
    public void translate(Collection<Position> origins, int dx, int dy, int dz) throws IOException {
        assertWritable();
        Set<Position> moving = new HashSet<>();
        for (Position pos : origins) if (placed.contains(pos)) moving.add(pos);
        if (moving.isEmpty()) return;
        List<String> ops = new ArrayList<>(moving.size() * 2);
        for (Position p : moving) ops.add("-" + encode(p));
        for (Position p : moving) ops.add("+" + encode(new Position(p.world(), p.x() + dx, p.y() + dy, p.z() + dz)));
        appendChanges(ops);
        applyOperations(ops);
    }

    private void assertWritable() throws IOException {
        if (locked) throw new IOException("Provenance journal is locked after a write failure");
    }
    private static String encode(Position p) {
        return p.world() + "," + p.x() + "," + p.y() + "," + p.z();
    }
    private static Position parse(String value) throws IOException {
        try {
            String[] a = value.split(",", -1);
            if (a.length != 4) throw new IllegalArgumentException("four fields required");
            return new Position(UUID.fromString(a[0]), Integer.parseInt(a[1]),
                Integer.parseInt(a[2]), Integer.parseInt(a[3]));
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid block position in provenance journal", invalid);
        }
    }
    private static String checksum(String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        return Long.toHexString(crc.getValue());
    }
    private void appendChanges(List<String> changes) throws IOException {
        String payload = String.join(";", changes);
        if (payload.length() > 256_000) throw new IOException("Oversized provenance transaction");
        String line = payload + "#" + checksum(payload) + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        try {
            Files.createDirectories(journal.toAbsolutePath().getParent());
            if (Files.exists(journal) && Files.size(journal) + bytes.length > MAX_JOURNAL_BYTES)
                throw new IOException("Provenance journal needs checkpoint; restart server safely");
            try (FileChannel ch = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) ch.write(buffer);
                ch.force(true);
            }
        } catch (IOException failure) {
            locked = true;
            throw failure;
        }
    }
    private void applyOperations(List<String> ops) throws IOException {
        for (String op : ops) {
            if (op.length() < 2 || (op.charAt(0) != '+' && op.charAt(0) != '-'))
                throw new IOException("Invalid provenance journal operation");
            Position p = parse(op.substring(1));
            if (op.charAt(0) == '+') placed.add(p); else placed.remove(p);
        }
    }
    private void replayJournal() throws IOException {
        if (!Files.exists(journal)) return;
        if (Files.size(journal) > MAX_JOURNAL_BYTES)
            throw new IOException("Provenance journal too large; inspect before starting quests");
        byte[] all = Files.readAllBytes(journal);
        if (all.length == 0) return;
        if (all[all.length - 1] != '\n') throw new IOException("Truncated provenance journal; quests locked");
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(all)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IOException("Invalid encoding in provenance journal", invalid);
        }
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) continue;
            int sep = line.lastIndexOf('#');
            if (sep <= 0 || !checksum(line.substring(0, sep)).equals(line.substring(sep + 1)))
                throw new IOException("Provenance journal checksum mismatch; quests locked");
            applyOperations(List.of(line.substring(0, sep).split(";", -1)));
        }
        if (placed.size() > MAX) throw new IOException("Provenance ledger exceeds capacity");
    }
    private void loadSnapshot() throws IOException {
        if (!Files.exists(file)) return;
        Properties data = new Properties();
        try (InputStream in = Files.newInputStream(file)) { data.loadFromXML(in); }
        try {
            if (!"1".equals(data.getProperty("format"))) throw new IllegalArgumentException("format");
            int count = Integer.parseInt(data.getProperty("count"));
            if (count < 0 || count > MAX) throw new IllegalArgumentException("count");
            for (int i=0;i<count;i++) {
                String raw = data.getProperty("p." + i);
                if (raw == null || !placed.add(parse(raw))) throw new IllegalArgumentException("entry/duplicate");
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid placed-block snapshot; quests locked", invalid);
        }
    }
    /** Call only after gameplay has stopped. Snapshot replacement and journal rotation are crash-safe. */
    public void checkpoint() throws IOException {
        assertWritable();
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "block-ledger-", ".tmp");
        try {
            Properties data = new Properties();
            data.setProperty("format", "1");
            data.setProperty("count", Integer.toString(placed.size()));
            int i = 0;
            for (Position p : placed) data.setProperty("p." + i++, encode(p));
            try (OutputStream out = Files.newOutputStream(temp)) { data.storeToXML(out, "TALQORIA placed blocks"); }
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.WRITE)) { ch.force(true); }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException noAtomic) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            // Mirror the durable snapshot through a temporary file, not an in-place
            // overwrite of .bak. The journal is rotated only after both snapshots exist.
            Path backupTemp = Files.createTempFile(dir, "block-backup-", ".tmp");
            try {
                Files.copy(file, backupTemp, StandardCopyOption.REPLACE_EXISTING);
                try (FileChannel ch = FileChannel.open(backupTemp, StandardOpenOption.WRITE)) { ch.force(true); }
                try { Files.move(backupTemp, backupPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException noAtomic) {
                    Files.move(backupTemp, backupPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(backupTemp); }
            Path empty = Files.createTempFile(dir, "block-journal-", ".tmp");
            try {
                try (FileChannel ch = FileChannel.open(empty, StandardOpenOption.WRITE)) { ch.force(true); }
                try { Files.move(empty, journal, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException noAtomic) { Files.move(empty, journal, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(empty); }
        } catch (IOException failure) {
            locked = true;
            throw failure;
        } finally { Files.deleteIfExists(temp); }
    }
}
