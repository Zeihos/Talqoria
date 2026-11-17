package ru.talqoria.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

/** Single outstanding trade intent. Fail closed on crashes; DO NOT auto-refund or replay. */
public final class MarketJournal {
    public record Pending(UUID id, UUID player, String side, String item, int quantity,
                          long amount, String openedAt) { }
    private final Path file;
    private Pending pending;
    public MarketJournal(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) pending = read();
    }
    public boolean hasPending() { return pending != null; }
    public Pending pending() { return pending; }
    public Pending begin(UUID player, String side, String item, int quantity, long amount) throws IOException {
        if (pending != null) throw new IllegalStateException("Unresolved market transaction");
        if (player == null || !("BUY".equals(side) || "SELL".equals(side)) ||
                item == null || !item.matches("[a-z0-9_]{1,40}") || quantity < 1 || quantity > 64 || amount < 1)
            throw new IllegalArgumentException("Invalid trade");
        Pending transaction = new Pending(UUID.randomUUID(), player, side, item, quantity, amount, Instant.now().toString());
        Properties p = new Properties();
        p.setProperty("format", "1");
        p.setProperty("id", transaction.id().toString());
        p.setProperty("player", player.toString());
        p.setProperty("side", side);
        p.setProperty("item", item);
        p.setProperty("quantity", Integer.toString(quantity));
        p.setProperty("amount", Long.toString(amount));
        p.setProperty("openedAt", transaction.openedAt());
        writeAtomic(p);
        pending = transaction;
        return transaction;
    }
    private void writeAtomic(Properties p) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "market-pending-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) { p.storeToXML(out, "TALQORIA pending trade"); }
            try (var channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private Pending read() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.loadFromXML(in); }
        try {
            if (!"1".equals(p.getProperty("format"))) throw new IllegalArgumentException("Unknown format");
            UUID id = UUID.fromString(p.getProperty("id"));
            UUID player = UUID.fromString(p.getProperty("player"));
            String side = p.getProperty("side");
            String item = p.getProperty("item");
            int quantity = Integer.parseInt(p.getProperty("quantity"));
            long amount = Long.parseLong(p.getProperty("amount"));
            String openedAt = p.getProperty("openedAt");
            if (!("BUY".equals(side) || "SELL".equals(side)) || item == null ||
                !item.matches("[a-z0-9_]{1,40}") || quantity < 1 || quantity > 64 ||
                amount < 1 || openedAt == null || openedAt.length() > 64)
                throw new IllegalArgumentException("Invalid fields");
            return new Pending(id, player, side, item, quantity, amount, openedAt);
        } catch (RuntimeException invalid) { throw new IOException("Invalid pending trade; operator must inspect file", invalid); }
    }
    /** Appends evidence BEFORE clearing pending marker. If write/remove fails, market stays locked. */
    public void complete(UUID id, String outcome) throws IOException {
        if (pending == null || !pending.id().equals(id) ||
                !("COMPLETED".equals(outcome) || "MANUALLY_RECONCILED".equals(outcome)))
            throw new IllegalArgumentException("Incorrect pending trade or outcome");
        String line = Instant.now() + " id=" + pending.id() + " player=" + pending.player() +
                " side=" + pending.side() + " item=" + pending.item() +
                " qty=" + pending.quantity() + " coins=" + pending.amount() + " " + outcome + "\n";
        Path audit = file.resolveSibling("market-audit.log");
        Files.createDirectories(audit.toAbsolutePath().getParent());
        try (var channel = java.nio.channels.FileChannel.open(audit,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.APPEND)) {
            java.nio.ByteBuffer bytes = java.nio.charset.StandardCharsets.UTF_8.encode(line);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        Files.delete(file);
        pending = null;
    }
}
