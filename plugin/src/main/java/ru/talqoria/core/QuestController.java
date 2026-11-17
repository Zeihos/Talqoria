package ru.talqoria.core;

import java.io.IOException;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.GameMode;

/** First playable daily progression loop. All quest mutations happen on the main thread. */
final class QuestController implements Listener {
    private final TalqoriaCorePlugin plugin;
    private final QuestStore quests;
    private final WalletStore wallets;
    private final TerritoryStore territories;
    private boolean dirty;
    private boolean locked;
    private final PlacedBlockLedger placed;
    private final NamespacedKey spawnedMob;

    QuestController(TalqoriaCorePlugin plugin, QuestStore quests, WalletStore wallets, TerritoryStore territories) {
        this.plugin = plugin; this.quests = quests; this.wallets = wallets; this.territories = territories;
        this.locked = plugin.questsLocked();
        this.placed = plugin.placedBlocks();
        this.spawnedMob = new NamespacedKey(plugin, "natural_quest_mob");
    }
    boolean locked() { return locked; }
    private void say(Player player, String message) { player.sendMessage("§2[TALQORIA] §7" + message); }
    private boolean eligible(Player player) { return player.getGameMode() == GameMode.SURVIVAL; }
    private static PlacedBlockLedger.Position pos(Block b) {
        return new PlacedBlockLedger.Position(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
    }
    private void failedProvenance(IOException failure) {
        locked = true;
        plugin.lockQuests("Placed-block ledger IO failure: " + failure.getMessage());
    }
    private static boolean tracked(Material m) {
        return switch (m) {
            case STONE, DEEPSLATE, OAK_LOG, SPRUCE_LOG, BIRCH_LOG, JUNGLE_LOG,
                 ACACIA_LOG, DARK_OAK_LOG, MANGROVE_LOG, CHERRY_LOG, PALE_OAK_LOG,
                 CRIMSON_STEM, WARPED_STEM -> true;
            default -> false;
        };
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (locked || placed == null || !tracked(event.getBlockPlaced().getType())) return;
        long started = System.nanoTime();
        try { placed.record(pos(event.getBlockPlaced())); }
        catch (IOException e) { failedProvenance(e); }
        finally { plugin.traceLedger("place", started); }
    }
    private void moveBlocks(List<Block> blocks, BlockFace face) {
        if (locked || placed == null) return;
        long started = System.nanoTime();
        try { placed.translate(blocks.stream().map(QuestController::pos).toList(),
            face.getModX(), face.getModY(), face.getModZ()); }
        catch (IOException e) { failedProvenance(e); }
        finally { plugin.traceLedger("piston", started); }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent e) { moveBlocks(e.getBlocks(), e.getDirection()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent e) { moveBlocks(e.getBlocks(), e.getDirection().getOppositeFace()); }
    private void removeExploded(List<Block> blocks) {
        if (locked || placed == null) return;
        long started = System.nanoTime();
        try { placed.consumeAll(blocks.stream().map(QuestController::pos).toList()); }
        catch (IOException e) { failedProvenance(e); }
        finally { plugin.traceLedger("explosion", started); }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent e) { removeExploded(e.blockList()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent e) { removeExploded(e.blockList()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobSpawn(CreatureSpawnEvent e) {
        // Fail closed: ONLY mobs spawned naturally while the plugin is running get the eligibility tag.
        // Mobs from spawners, commands, eggs and older worlds are not eligible for quest progress.
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) {
            e.getEntity().getPersistentDataContainer().set(spawnedMob, PersistentDataType.BYTE, (byte) 1);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (locked) return;
        Material m = event.getBlock().getType();
        if (placed != null) {
            long started = System.nanoTime();
            try { if (placed.consume(pos(event.getBlock()))) return; }
            catch (IOException e) { failedProvenance(e); return; }
            finally { plugin.traceLedger("break", started); }
        }
        // Creative/adventure/spectator breaks must clear stale provenance,
        // but never count toward Survival-only daily objectives.
        if (!eligible(event.getPlayer())) return;
        QuestStore.Objective objective = switch (m) {
            case STONE, DEEPSLATE -> QuestStore.Objective.STONE;
            case OAK_LOG, SPRUCE_LOG, BIRCH_LOG, JUNGLE_LOG, ACACIA_LOG,
                 DARK_OAK_LOG, MANGROVE_LOG, CHERRY_LOG, PALE_OAK_LOG,
                 CRIMSON_STEM, WARPED_STEM -> QuestStore.Objective.LOGS;
            default -> null;
        };
        if (objective != null && quests.record(event.getPlayer().getUniqueId(), objective)) dirty = true;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        if (locked || event.getEntityType() != EntityType.ZOMBIE ||
            !event.getEntity().getPersistentDataContainer().has(spawnedMob, PersistentDataType.BYTE)) return;
        Player killer = event.getEntity().getKiller();
        if (killer != null && eligible(killer) && quests.record(killer.getUniqueId(), QuestStore.Objective.ZOMBIES)) dirty = true;
    }
    void execute(Player player, String[] args) {
        if (locked || plugin.walletLocked()) {
            say(player, "Задания недоступны из-за ошибки хранения данных. Обратись к администрации."); return;
        }
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) {
            say(player, "Ежедневные задания (сброс в 00:00 UTC):");
            for (QuestStore.Status status : quests.statuses(player.getUniqueId())) {
                QuestStore.Quest q = status.quest();
                say(player, q.id() + " · " + q.title() + ": " + status.progress() + "/" + q.target() +
                        " · " + q.coins() + " монет + " + q.townXp() + " влияния" +
                        (status.claimed() ? " · получено" : ""));
            }
            say(player, "Получить награду: /tq quest claim <id>");
            return;
        }
        if (args.length != 3 || !args[1].equalsIgnoreCase("claim")) {
            say(player, "Использование: /tq quest [list | claim <miner|lumber|hunter>]"); return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        UUID playerId = player.getUniqueId();
        QuestStore.ClaimResult canClaim = quests.canClaim(playerId, id);
        if (canClaim != QuestStore.ClaimResult.READY) {
            say(player, switch (canClaim) {
                case INCOMPLETE -> "Задание ещё не выполнено.";
                case ALREADY_CLAIMED -> "Эта награда уже получена сегодня.";
                case UNKNOWN -> "Неизвестное задание.";
                default -> "Не удалось получить награду.";
            });
            return;
        }
        QuestStore.Quest q = quests.find(id);
        String receipt = quests.receipt(playerId, id);
        WalletStore.RewardGrant result = wallets.creditQuestOnce(playerId, receipt, q.coins());
        if (result != WalletStore.RewardGrant.OK && result != WalletStore.RewardGrant.ALREADY) {
            if (result == WalletStore.RewardGrant.IO_ERROR) plugin.lockWallets("Quest reward failed to persist");
            say(player, "Не удалось начислить монеты: " + result + ".");
            return;
        }
        if (!quests.markClaimed(playerId, id) || !persist()) {
            say(player, "Монеты сохранены, но журнал заданий не записан. Сообщи администрации. Повтор не начислит монеты.");
            return;
        }
        int levelBefore = 0, levelAfter = 0;
        if (territories != null && !plugin.territoryLocked()) {
            TerritoryStore.Town town = territories.townFor(playerId);
            if (town != null) {
                levelBefore = town.level();
                TerritoryStore.Result contributed = territories.contribute(playerId, receipt, q.townXp());
                if (contributed == TerritoryStore.Result.OK) {
                    if (!plugin.persistTerritories()) {
                        say(player, "Влияние поселения не сохранено; сообщи администрации.");
                        return;
                    }
                    levelAfter = town.level();
                }
            }
        }
        say(player, "Награда «" + q.title() + "»: +" + q.coins() + " монет!" +
                (levelAfter > 0 ? " +" + q.townXp() + " влияния поселения." : ""));
        if (levelAfter > levelBefore) say(player, "Твоё поселение достигло уровня " + levelAfter + "!");
    }
    boolean persist() {
        if (locked) return false;
        try { quests.save(); dirty = false; return true; }
        catch (IOException e) {
            locked = true;
            plugin.getLogger().severe("QUEST DATABASE SAVE FAILED. Quests locked: " + e.getMessage());
            return false;
        }
    }
    /** Persist quest progress on disconnect rather than relying only on the timer. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { saveIfDirty(); }
    void saveIfDirty() { if (dirty) persist(); }
}
