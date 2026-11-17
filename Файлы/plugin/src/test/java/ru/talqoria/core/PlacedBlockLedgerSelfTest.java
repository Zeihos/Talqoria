package ru.talqoria.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Self-contained regression tests; no Paper dependency needed. */
public final class PlacedBlockLedgerSelfTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("talqoria-ledger-");
        try {
            Path file = dir.resolve("placed-blocks.xml");
            UUID world = UUID.randomUUID();
            var a = new PlacedBlockLedger.Position(world, 1, 64, 1);
            var b = new PlacedBlockLedger.Position(world, 2, 64, 1);
            var c = new PlacedBlockLedger.Position(world, 3, 64, 1);
            var other = new PlacedBlockLedger.Position(world, 4, 64, 1);
            var ledger = new PlacedBlockLedger(file);
            ledger.record(a);
            ledger.record(b);
            ledger.record(c);
            check(ledger.consumeAll(List.of(a, a, b, other)) == 2, "batch deduplication");
            check(ledger.size() == 1 && ledger.contains(c), "remaining state");
            var reloaded = new PlacedBlockLedger(file);
            check(reloaded.size() == 1 && reloaded.contains(c), "restart persistence");
            check(reloaded.consumeAll(List.of(other)) == 0, "unknown positions");
            check(reloaded.consumeAll(List.of(c)) == 1, "final removal");
            check(new PlacedBlockLedger(file).size() == 0, "empty snapshot persisted");

            Path bad = dir.resolve("unwritable.xml");
            var failing = new PlacedBlockLedger(bad);
            failing.record(a);
            Files.delete(bad);
            Files.createDirectory(bad);
            boolean failed = false;
            try { failing.consumeAll(List.of(a)); }
            catch (IOException expected) { failed = true; }
            check(failed, "IO failure must propagate");
            check(failing.contains(a), "IO failure must roll back memory");
            System.out.println("PASS: ledger batching, restart, no-op and IO rollback");
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path entry : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(entry);
            }
        }
    }
}
