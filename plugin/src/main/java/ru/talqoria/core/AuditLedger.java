package ru.talqoria.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Sequential tamper-EVIDENT staff activity ledger (SHA-256 hash chain).
 * Protects against accidental edits/truncation in the middle; NOT a signature.
 * An administrator with disk access can rewrite the chain. Back up the ledger externally.
 * Designed for main-thread use and deliberately fails closed on malformed/damaged data.
 */
public final class AuditLedger {
    public record Entry(long number, String when, String actor, String action,
                        String target, String detail, String previous, String hash) {}
    private static final String GENESIS = "0".repeat(64);
    private static final long MAX_FILE_BYTES = 20_000_000;
    private static final int MAX_RECORDS = 100_000;
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private String tip = GENESIS;
    private boolean locked;

    public AuditLedger(Path file) throws IOException {
        this.file = file;
        load();
    }
    public boolean isLocked() { return locked; }
    public long count() { return entries.size(); }
    public String tip() { return tip; }
    public List<Entry> tail(int max) {
        int count = Math.max(0, Math.min(20, max));
        return List.copyOf(entries.subList(Math.max(0, entries.size() - count), entries.size()));
    }
    /** Records an action BEFORE granting sensitive access. IOException => deny action. */
    public synchronized Entry append(String actor, String action, String target, String detail) throws IOException {
        if (locked) throw new IOException("Audit ledger is locked after an error");
        if (entries.size() >= MAX_RECORDS) { locked = true; throw new IOException("Audit record limit reached"); }
        String a = safe(actor, 64), op = safe(action, 48), t = safe(target, 64), d = safe(detail, 160);
        if (a.isEmpty() || op.isEmpty()) throw new IllegalArgumentException("Actor/action required");
        long seq = entries.size() + 1L;
        String when = Instant.now().toString();
        String payload = seq + "\t" + when + "\t" + a + "\t" + op + "\t" + t + "\t" + d + "\t" + tip;
        String hash = sha(payload);
        byte[] bytes = (payload + "\t" + hash + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            if (Files.exists(file) && Files.size(file) > MAX_FILE_BYTES - bytes.length) {
                throw new IOException("Audit file size limit reached; archive offline before running server");
            }
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
        } catch (IOException ex) { locked = true; throw ex; }
        Entry e = new Entry(seq, when, a, op, t, d, tip, hash);
        entries.add(e);
        tip = hash;
        return e;
    }
    private void load() throws IOException {
        if (!Files.exists(file)) return;
        if (Files.size(file) == 0 || Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("Empty/oversized staff audit ledger; manual investigation required");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes[bytes.length - 1] != '\n') throw new IOException("Incomplete final audit record");
        // Reject malformed UTF-8 bytes rather than silently replacing bytes on read.
        String input;
        try {
            input = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IOException("Invalid UTF-8 audit data", invalid);
        }
        String[] lines = input.split("\n", -1);
        if (lines.length - 1 > MAX_RECORDS) throw new IOException("Too many staff audit records");
        String previous = GENESIS;
        for (int i = 0; i < lines.length - 1; i++) {
            String[] parts = lines[i].split("\t", -1);
            if (parts.length != 8 || !parts[0].equals(Long.toString(i + 1L)) ||
                !parts[6].equals(previous) || !parts[7].equals(sha(String.join("\t", java.util.Arrays.copyOf(parts, 7))))) {
                throw new IOException("Staff audit integrity failure at entry " + (i + 1));
            }
            try { Instant.parse(parts[1]); }
            catch (RuntimeException invalid) { throw new IOException("Invalid audit timestamp at entry " + (i + 1), invalid); }
            if (parts[2].isEmpty() || parts[3].isEmpty()) throw new IOException("Invalid audit fields at entry " + (i + 1));
            for (int j = 2; j <= 5; j++) {
                if (!parts[j].equals(safe(parts[j], new int[]{0,0,64,48,64,160}[j])))
                    throw new IOException("Untrusted audit field at entry " + (i + 1));
            }
            entries.add(new Entry(i+1L, parts[1], parts[2], parts[3], parts[4], parts[5], parts[6], parts[7]));
            previous = parts[7];
        }
        tip = previous;
    }
    private static String safe(String value, int limit) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        value.codePoints().forEach(cp -> {
            if (cp >= 32 && cp != 127 && cp != '\t' && cp != '\n' && cp != '\r' && out.length() < limit)
                out.appendCodePoint(cp);
        });
        return out.toString();
    }
    private static String sha(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
