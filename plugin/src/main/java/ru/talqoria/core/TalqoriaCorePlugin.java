package ru.talqoria.core;

import java.io.IOException;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class TalqoriaCorePlugin extends JavaPlugin implements Listener {
    private WalletStore wallets;
    private StatusBridge bridge;
    private TerritoryStore territories;
    private QuestStore quests;
    private QuestController questController;
    private boolean walletLockdown;
    private boolean questLockdown;
    private boolean territoryLockdown;
    private MarketJournal marketJournal;
    private boolean marketLockdown;
    private PlacedBlockLedger placedBlocks;
    private AuditLedger staffAudit;
    private boolean auditLockdown;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            staffAudit = new AuditLedger(getDataFolder().toPath().resolve("staff-audit.tsv"));
        } catch (IOException invalid) {
            auditLockdown = true;
            getLogger().severe("STAFF AUDIT INVALID: privileged actions blocked: " + invalid.getMessage());
        }
        try {
            wallets = new WalletStore(
                    getDataFolder().toPath().resolve("players.yml"),
                    getConfig().getLong("economy.starting-balance", 100),
                    getConfig().getLong("economy.daily-reward", 25),
                    getConfig().getLong("economy.max-transfer", 1_000_000));
            if (wallets.recoveredBackup()) {
                walletLockdown = true;
                getLogger().severe("WALLET RECOVERY LOCKDOWN: loading read-only .bak snapshot. "
                    + "Stop the server, compare players.yml with players.yml.bak, and reconcile manually. "
                    + "NO wallet payouts/transfers/market activity permitted.");
            }
        } catch (IOException ex) {
            getLogger().severe("WALLET LOAD FAILED - disabling TalqoriaCore: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            marketJournal = new MarketJournal(getDataFolder().toPath().resolve("market-pending.xml"));
            if (marketJournal.hasPending()) {
                marketLockdown = true;
                getLogger().severe("UNRESOLVED MARKET TRADE: " + marketJournal.pending().id()
                        + ". Market locked until manual reconciliation.");
            }
        } catch (IOException invalid) {
            marketLockdown = true;
            getLogger().severe("MARKET JOURNAL INVALID; market locked: " + invalid.getMessage());
        }
        try {
            placedBlocks = new PlacedBlockLedger(getDataFolder().toPath().resolve("placed-blocks.xml"));
        } catch (IOException invalid) {
            questLockdown = true;
            getLogger().severe("PLACED BLOCK LEDGER INVALID. Quests locked: " + invalid.getMessage());
        }
        try {
            quests = new QuestStore(getDataFolder().toPath().resolve("quests.xml"));
            if (quests.recoveredBackup()) {
                getLogger().severe("Quest data recovered from backup. Rewards LOCKED pending inspection.");
                questLockdown = true;
            }
        } catch (IOException ex) {
            getLogger().severe("QUEST LOAD FAILED; rewards locked: " + ex.getMessage());
            questLockdown = true;
        }
        try {
            territories = new TerritoryStore(getDataFolder().toPath().resolve("territories.xml"),
                    getConfig().getInt("claims.max-chunks-per-town", 8),
                    getConfig().getInt("claims.max-members", 8),
                    getConfig().getInt("claims.max-guests", 16));
            if (territories.recoveredBackup()) {
                getLogger().severe("Territory file invalid, restored state from .bak. Check the data files.");
                territoryLockdown = true; // require manual inspection before enabling edits
            }
        } catch (IOException invalid) {
            getLogger().severe("TERRITORY DATA LOAD FAILED. WORLD EDITS LOCKED: " + invalid.getMessage());
            territoryLockdown = true;
        }
        if (territories == null) {
            // Database cannot be trusted; every write and edit must be denied.
            getServer().getPluginManager().registerEvents(new TerritoryProtection(null, this), this);
        } else {
            getServer().getPluginManager().registerEvents(new TerritoryProtection(territories, this), this);
        }
        PluginCommand command = Objects.requireNonNull(getCommand("tq"), "tq not declared in plugin.yml");
        if (quests != null) {
            questController = new QuestController(this, quests, wallets, territories);
            getServer().getPluginManager().registerEvents(questController, this);
            // Batching saves every 60 seconds instead of one disk write per mined block.
            Bukkit.getScheduler().runTaskTimer(this, questController::saveIfDirty, 1200L, 1200L);
        }
        TalqoriaCommand executor = new TalqoriaCommand(this, wallets, territories, questController);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        Bukkit.getPluginManager().registerEvents(this, this);

        // Join-only initialization; players present during /reload are handled too.
        boolean dirty = false;
        if (!walletLockdown)
            for (Player player : Bukkit.getOnlinePlayers()) dirty |= wallets.ensurePlayer(player.getUniqueId());
        if (dirty) persistWallets();
        if (!Bukkit.getOnlineMode()) {
            getLogger().warning("OFFLINE MODE ACTIVE: unauthenticated UUIDs can differ when enabling online-mode.");
            getLogger().warning("Keep the test server private and reconcile account-linked data before changing authentication.");
        }
        bridge = new StatusBridge(this);
        bridge.start();
        getLogger().info("TalqoriaCore v0.17 started. Territory lockdown: " + territoryLockdown
            + ", wallet lockdown: " + walletLockdown + ", audit lockdown: " + auditLockdown);
    }

    @Override
    public void onDisable() {
        if (bridge != null) bridge.stop();
        if (placedBlocks != null && !placedBlocks.isLocked()) {
            try { placedBlocks.checkpoint(); }
            catch (IOException e) { getLogger().severe("BLOCK JOURNAL CHECKPOINT FAILED: " + e.getMessage()); }
        }
        if (questController != null && !questLockdown) questController.saveIfDirty();
        if (wallets != null && !walletLockdown) persistWallets();
        if (territories != null && !territoryLockdown) persistTerritories();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!walletLockdown && wallets.ensurePlayer(player.getUniqueId())) persistWallets();
        if (getConfig().getBoolean("messages.welcome", true)) {
            player.sendMessage(ChatColor.GREEN + "[TALQORIA] " + ChatColor.WHITE
                    + "Добро пожаловать! Напиши " + ChatColor.AQUA + "/tq help"
                    + ChatColor.WHITE + " для списка команд.");
        }
    }

    public boolean persistTerritories() {
        if (territoryLockdown || territories == null) return false;
        try { territories.save(); return true; }
        catch (IOException ex) {
            getLogger().severe("FAILED SAVING territories.xml: " + ex.getMessage());
            territoryLockdown = true;
            // Editing commands are blocked, but protection listener has the old state.
            getLogger().severe("Stop server and investigate storage. Do not continue accepting players.");
            return false;
        }
    }

    public boolean persistWallets() {
        try {
            wallets.save();
            return true;
        } catch (IOException e) {
            // Warn operators: continuing after failed saving risks loss on restart.
            walletLockdown = true;
            getLogger().severe("WALLET DATA LOCKED: Cannot save players.yml: " + e.getMessage());
            return false;
        }
    }

    /** Log slow file operations without blocking the server on additional I/O. */
    public void traceLedger(String operation, long startedNanos) {
        long durationMs = (System.nanoTime() - startedNanos) / 1_000_000;
        long warn = Math.max(5, getConfig().getLong("diagnostics.slow-ledger-ms", 30));
        if (durationMs >= warn) getLogger().warning("SLOW_LEDGER " + operation + " " + durationMs + "ms");
    }
    public PlacedBlockLedger placedBlocks() { return placedBlocks; }
    public AuditLedger staffAudit() { return staffAudit; }
    public boolean auditLocked() { return auditLockdown || staffAudit == null || staffAudit.isLocked(); }
    /** Never perform privileged state-changing actions if the audit intent cannot be persisted. */
    public boolean auditAction(String actor, String action, String target, String detail) {
        if (auditLocked()) return false;
        try { staffAudit.append(actor, action, target, detail); return true; }
        catch (IOException | IllegalArgumentException failure) {
            auditLockdown = true;
            getLogger().severe("STAFF AUDIT WRITE FAILED: " + failure.getMessage());
            return false;
        }
    }
    public void lockQuests(String reason) {
        questLockdown = true;
        getLogger().severe("QUESTS LOCKED: " + reason);
    }
    public MarketJournal marketJournal() { return marketJournal; }
    public boolean marketLocked() {
        return walletLockdown || marketLockdown || marketJournal == null || marketJournal.hasPending();
    }
    public boolean marketEnabled() { return getConfig().getBoolean("market.enabled", false); }
    public void lockMarket(String reason) {
        marketLockdown = true;
        getLogger().severe("MARKET LOCKED: " + reason);
    }
    public boolean territoryLocked() { return territoryLockdown; }
    public boolean walletLocked() { return walletLockdown; }
    public boolean questsLocked() { return questLockdown || quests == null || (questController != null && questController.locked()); }
    public void lockWallets(String reason) { walletLockdown = true; getLogger().severe("WALLETS LOCKED: " + reason); }

    public Location readSpawn() {
        ConfigurationSection section = getConfig().getConfigurationSection("spawn");
        if (section != null) {
            World world = Bukkit.getWorld(section.getString("world", ""));
            if (world != null) {
                return new Location(world, section.getDouble("x"), section.getDouble("y"),
                        section.getDouble("z"), (float) section.getDouble("yaw"),
                        (float) section.getDouble("pitch"));
            }
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    public void setSpawn(Location location) {
        getConfig().set("spawn.world", Objects.requireNonNull(location.getWorld()).getName());
        getConfig().set("spawn.x", location.getX());
        getConfig().set("spawn.y", location.getY());
        getConfig().set("spawn.z", location.getZ());
        getConfig().set("spawn.yaw", location.getYaw());
        getConfig().set("spawn.pitch", location.getPitch());
        saveConfig();
    }
}
