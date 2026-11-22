package ru.talqoria.core;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read-only triage for a stopped server. Does not parse Paper's wallets YAML. */
public final class OfflineInspector {
    private OfflineInspector() {}
    /** 0 = no issue detected in files checked, 2 = unresolved trade, 3 = broken audit or input error. */
    public static int inspect(Path folder, PrintStream out) {
        if (folder == null || !Files.isDirectory(folder)) {
            out.println("ERROR: TalqoriaCore data folder does not exist: " + folder);
            return 3;
        }
        out.println("TALQORIA OFFLINE DATA INSPECTOR v0.19 (READ ONLY)");
        out.println("Folder: " + folder.toAbsolutePath().normalize());
        out.println("Ensure Paper is stopped. A live check can give inconsistent results.");
        int result = 0;
        Path audit = folder.resolve("staff-audit.tsv");
        if (Files.exists(audit)) {
            try {
                AuditLedger ledger = new AuditLedger(audit);
                out.println("STAFF AUDIT: VERIFIED " + ledger.count() + " entries; tip " + ledger.tip());
            } catch (IOException | RuntimeException invalid) {
                out.println("STAFF AUDIT: ERROR - " + invalid.getMessage());
                result = 3;
            }
        } else {
            out.println("STAFF AUDIT: not present (normal until first staff action on a fresh installation)");
        }
        Path trade = folder.resolve("market-pending.xml");
        if (Files.exists(trade)) {
            try {
                MarketJournal journal = new MarketJournal(trade);
                MarketJournal.Pending p = journal.pending();
                if (p != null) {
                    out.println("MARKET: UNRESOLVED - trade=" + p.id() + " player=" + p.player()
                        + " side=" + p.side() + " item=" + p.item() + " qty=" + p.quantity()
                        + " coins=" + p.amount());
                    out.println("DO NOT delete market-pending.xml, issue an automatic refund, or enable market.");
                    result = Math.max(result, 2);
                }
            } catch (IOException | RuntimeException invalid) {
                out.println("MARKET: INVALID - " + invalid.getMessage());
                result = 3;
            }
        } else {
            out.println("MARKET: no pending journal file detected");
        }
        for (String filename : new String[]{"players.yml", "territories.xml", "quests.xml"}) {
            Path primary = folder.resolve(filename);
            Path backup = folder.resolve(filename + ".bak");
            if (!Files.exists(primary) && Files.exists(backup)) {
                out.println(filename + ": RECOVERY REQUIRED — primary missing, backup present. Do NOT start data edits.");
                result = 3;
            } else if (Files.isRegularFile(primary)) {
                try {
                    if (Files.size(primary) == 0) {
                        out.println(filename + ": ERROR — empty primary file");
                        result = 3;
                    } else out.println(filename + ": present (contents are NOT validated here)");
                } catch (IOException e) {
                    out.println(filename + ": ERROR — " + e.getMessage());
                    result = 3;
                }
            } else out.println(filename + ": not present (normal for a brand new server)");
        }
        // Verify the actual territory parser in read-only mode, including the
        // primary/backup revision and integrity seal, before the server restarts.
        Path townFile = folder.resolve("territories.xml");
        Path townBackup = folder.resolve("territories.xml.bak");
        if (Files.exists(townFile) || Files.exists(townBackup)) {
            try {
                TerritoryStore store = new TerritoryStore(townFile, 1_000_000, 250_000, 250_000);
                if (store.recoveredBackup()) {
                    out.println("TERRITORIES: RECOVERY REQUIRED — primary/backup inconsistent; claims stay locked");
                    result = 3;
                } else {
                    out.println("TERRITORIES: VERIFIED " + store.towns().size() + " towns (read only)");
                }
            } catch (IOException | RuntimeException invalid) {
                out.println("TERRITORIES: INVALID — " + invalid.getMessage());
                result = 3;
            }
        }
        // The same WAL parser used by the plugin verifies format, checksum and replay.
        // Reading this state does not checkpoint, overwrite or migrate saved files.
        Path snapshot = folder.resolve("placed-blocks.xml");
        Path provenanceBackup = folder.resolve("placed-blocks.xml.bak");
        Path journalFile = folder.resolve("placed-blocks.journal");
        if (Files.exists(snapshot) || Files.exists(provenanceBackup) || Files.exists(journalFile)) {
            try {
                PlacedBlockLedger ledger = new PlacedBlockLedger(snapshot);
                out.println("BLOCK PROVENANCE: VERIFIED " + ledger.size() + " tracked positions");
            } catch (IOException | RuntimeException invalid) {
                out.println("BLOCK PROVENANCE: RECOVERY REQUIRED - " + invalid.getMessage()
                    + "; do not overwrite the snapshot, .bak or journal");
                result = 3;
            }
        } else {
            out.println("BLOCK PROVENANCE: not present (normal for a new server)");
        }
        out.println("NOTE: This is not a Paper runtime test, wallet validator or full filesystem backup verifier.");
        out.println(result == 0 ? "RESULT: no issues detected in checked records" :
            result == 2 ? "RESULT: ACTION REQUIRED - reconcile pending trade offline" :
                          "RESULT: ERROR - stop and investigate data before bringing server online");
        return result;
    }
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java ru.talqoria.core.OfflineInspector <plugins/TalqoriaCore data folder>");
            System.exit(3);
        }
        System.exit(inspect(Path.of(args[0]), System.out));
    }
}
