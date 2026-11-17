package ru.talqoria.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/** Paper-independent persistence regression test. Run with Java 21+. */
public final class PlacedBlockLedgerSelfTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("talqoria-ledger-test-");
        try {
            Path xml = dir.resolve("placed-blocks.xml");
            Path journal = dir.resolve("placed-blocks.journal");
            UUID world = UUID.randomUUID();
            var a = new PlacedBlockLedger.Position(world, 1, 64, 1);
            var b = new PlacedBlockLedger.Position(world, 2, 64, 1);
            var c = new PlacedBlockLedger.Position(world, 3, 64, 1);
            var d = new PlacedBlockLedger.Position(world, 4, 64, 1);
            var ledger = new PlacedBlockLedger(xml);
            ledger.record(a);
            ledger.record(b);
            ledger.record(c);
            check(Files.size(journal) > 0, "durable journal");
            check(ledger.consumeAll(List.of(a, a, b, d)) == 2, "batch and dedupe");
            check(ledger.contains(c) && ledger.size() == 1, "batch state");
            var restarted = new PlacedBlockLedger(xml);
            check(restarted.contains(c) && restarted.size() == 1, "restart replay");
            check(restarted.consumeAll(List.of(d)) == 0, "unknown position no-op");
            restarted.translate(List.of(c), 1, 0, 0);
            check(!restarted.contains(c) && restarted.contains(d), "piston translation");
            var recovered = new PlacedBlockLedger(xml);
            check(recovered.contains(d), "piston replay");
            recovered.checkpoint();
            check(Files.size(journal) == 0, "checkpoint rotated journal");
            Path backup = dir.resolve("placed-blocks.xml.bak");
            check(Files.size(backup) > 0, "checkpoint wrote durable backup");
            byte[] savedBackup = Files.readAllBytes(backup);
            check(new PlacedBlockLedger(xml).contains(d), "snapshot reload");
            var pending = new PlacedBlockLedger(xml);
            pending.record(a);
            byte[] oldJournal = Files.readAllBytes(journal);
            pending.checkpoint();
            Files.write(journal, oldJournal);
            var rerun = new PlacedBlockLedger(xml);
            check(rerun.contains(a) && rerun.contains(d) && rerun.size() == 2,
                "checkpoint interrupted before journal rotation");
            rerun.checkpoint();
            byte[] beforeDeletion = Files.readAllBytes(backup);
            // Missing primary plus backup must stop startup, never reinitialise
            // an empty ledger and overwrite a player's progress on shutdown.
            Files.delete(xml);
            boolean missingPrimaryLocked = false;
            try { new PlacedBlockLedger(xml); }
            catch (IOException e) { missingPrimaryLocked = true; }
            check(missingPrimaryLocked, "missing primary with backup fails closed");
            check(Files.exists(backup) && java.util.Arrays.equals(Files.readAllBytes(backup), beforeDeletion),
                "backup preserved byte-for-byte after failed startup");
            Files.copy(backup, xml);
            check(new PlacedBlockLedger(xml).contains(d), "operator may restore verified backup");
            // Prior v0.13 snapshots without a .bak must remain readable.
            Path legacyDir = Files.createDirectory(dir.resolve("legacy"));
            Path legacy = legacyDir.resolve("placed-blocks.xml");
            Files.copy(xml, legacy);
            check(new PlacedBlockLedger(legacy).contains(d), "legacy snapshot without backup opens");
            new PlacedBlockLedger(legacy).checkpoint();
            check(Files.exists(legacyDir.resolve("placed-blocks.xml.bak")), "legacy checkpoint adds backup");
            Files.writeString(journal, "badline\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            boolean corrupt = false;
            try { new PlacedBlockLedger(xml); } catch (IOException e) { corrupt = true; }
            check(corrupt, "invalid checksum rejected");
            Files.writeString(journal, "cut-off", StandardOpenOption.TRUNCATE_EXISTING);
            corrupt = false;
            try { new PlacedBlockLedger(xml); } catch (IOException e) { corrupt = true; }
            check(corrupt, "truncated journal rejected");
            Files.delete(journal);
            var locked = new PlacedBlockLedger(xml);
            Files.createDirectory(journal);
            boolean failed = false;
            try { locked.record(b); } catch (IOException e) { failed = true; }
            check(failed && locked.isLocked() && !locked.contains(b), "write failure must lock");
            System.out.println("PASS: WAL safety, replay, batch, translation, checkpoint, corruption and IO lockdown");
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(p);
            }
        }
    }
}
