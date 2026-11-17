package ru.talqoria.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

/** OfflineInspector must be read-only and fail closed on damaged provenance data. */
public final class OfflineInspectorSelfTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static int inspect(Path dir) {
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int result = OfflineInspector.inspect(dir, stream);
            check(output.toString(StandardCharsets.UTF_8).contains("v0.14"),
                    "inspector must show version 0.14");
            return result;
        }
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("talqoria-inspector-test-");
        try {
            Path file = dir.resolve("placed-blocks.xml");
            Path journal = dir.resolve("placed-blocks.journal");
            var ledger = new PlacedBlockLedger(file);
            var a = new PlacedBlockLedger.Position(UUID.randomUUID(), 8, 64, 8);
            ledger.record(a);
            ledger.checkpoint();
            byte[] snapshot = Files.readAllBytes(file);
            byte[] backup = Files.readAllBytes(dir.resolve("placed-blocks.xml.bak"));
            check(inspect(dir) == 0, "valid provenance should pass");
            check(java.util.Arrays.equals(snapshot, Files.readAllBytes(file)), "inspector must not rewrite primary");
            check(java.util.Arrays.equals(backup, Files.readAllBytes(dir.resolve("placed-blocks.xml.bak"))),
                    "inspector must not rewrite backup");
            Files.delete(file);
            check(inspect(dir) == 3, "missing primary with backup must fail");
            check(java.util.Arrays.equals(backup, Files.readAllBytes(dir.resolve("placed-blocks.xml.bak"))),
                    "recovery inspection must preserve backup");
            Files.copy(dir.resolve("placed-blocks.xml.bak"), file);
            Files.writeString(journal, "invalid-checksum#12345678\n", StandardCharsets.UTF_8);
            check(inspect(dir) == 3, "invalid journal must fail");
            Files.writeString(journal, "partial", StandardCharsets.UTF_8);
            check(inspect(dir) == 3, "truncated journal must fail");
            Files.delete(journal);
            check(inspect(dir) == 0, "repaired provenance passes");
            System.out.println("PASS: offline snapshot verification, recovery and read-only inspection");
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path file : walk.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            }
        }
    }
}
