package ru.talqoria.core;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;

/** Conservative MVP protection. Not a replacement for a production anti-grief suite. */
public final class TerritoryProtection implements Listener {
    private final TerritoryStore db;
    private final TalqoriaCorePlugin plugin;
    TerritoryProtection(TerritoryStore db, TalqoriaCorePlugin plugin) { this.db = db; this.plugin = plugin; }
    private boolean lockdown() { return plugin.territoryLocked() || db == null; }

    public static TerritoryStore.ChunkKey chunk(Location location) {
        return new TerritoryStore.ChunkKey(location.getWorld().getUID(),
                location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }
    private boolean spawnProtected(Location location) {
        if (!plugin.getConfig().getBoolean("spawn-protection.enabled", true)) return false;
        Location spawn = plugin.readSpawn();
        if (spawn == null || !spawn.getWorld().equals(location.getWorld())) return false;
        int radius = Math.max(0, plugin.getConfig().getInt("spawn-protection.radius-blocks", 64));
        return radius > 0 && Math.abs(location.getX() - spawn.getX()) <= radius &&
                Math.abs(location.getZ() - spawn.getZ()) <= radius;
    }
    private boolean protectedFrom(Player player, Location location) {
        // A corrupt or unavailable claim database must deny edits to EVERYONE, even operators.
        // Staff bypass is for normal administration, not for bypassing storage failures.
        if (lockdown()) return ClaimAccessPolicy.denyEdit(true, player.hasPermission("talqoria.claims.bypass"), false, false);
        if (player.hasPermission("talqoria.claims.bypass")) return false;
        return ClaimAccessPolicy.denyEdit(false, false, spawnProtected(location),
                db.canBuild(player.getUniqueId(), chunk(location)));
    }
    private boolean protectedFrom(Player player, Block block) { return protectedFrom(player, block.getLocation()); }
    private boolean worldProtected(Location location) {
        return lockdown() || spawnProtected(location) || db.isProtected(chunk(location));
    }
    private boolean worldProtected(Block block) { return worldProtected(block.getLocation()); }

    private boolean across(Location from, Location to) {
        if (lockdown()) return true;
        // Never let a transfer move protected resources between different worlds.
        // Bukkit locations normally have a world, but plugin-created virtual
        // inventories may not have a usable physical location.
        if (ClaimAccessPolicy.denyUnlocatedTransfer(false,
                from != null && from.getWorld() != null,
                to != null && to.getWorld() != null))
            return true;
        if (!from.getWorld().equals(to.getWorld()))
            return worldProtected(from) || worldProtected(to);
        return ClaimAccessPolicy.denyBoundaryTransfer(
            false, spawnProtected(from), spawnProtected(to),
            db.claimOwner(chunk(from)), db.claimOwner(chunk(to)));
    }
    private boolean across(Block a, Block b) {
        return across(a.getLocation(), b.getLocation());
    }
    private void deny(Player player) {
        player.sendActionBar(net.kyori.adventure.text.Component.text(
                lockdown() ? "Территории заблокированы: ошибка загрузки. Сообщи администрации." :
                           "Чужая территория: строительство и взаимодействие запрещены."));
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) {
        if (protectedFrom(e.getPlayer(), e.getBlock())) { e.setCancelled(true); deny(e.getPlayer()); }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void placeBlock(BlockPlaceEvent e) {
        if (protectedFrom(e.getPlayer(), e.getBlockPlaced()) ||
            (e instanceof org.bukkit.event.block.BlockMultiPlaceEvent multi &&
             multi.getReplacedBlockStates().stream().anyMatch(s -> protectedFrom(e.getPlayer(), s.getBlock())))) {
            e.setCancelled(true); deny(e.getPlayer());
        }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void interact(PlayerInteractEvent e) {
        if ((e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.PHYSICAL) && e.getClickedBlock() != null &&
                protectedFrom(e.getPlayer(), e.getClickedBlock())) {
            e.setCancelled(true);
        }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void interactEntity(PlayerInteractEntityEvent e) {
        if (protectedFrom(e.getPlayer(), e.getRightClicked().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void armor(PlayerArmorStandManipulateEvent e) {
        if (protectedFrom(e.getPlayer(), e.getRightClicked().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void bucketFill(PlayerBucketFillEvent e) {
        if (protectedFrom(e.getPlayer(), e.getBlock()) || protectedFrom(e.getPlayer(), e.getBlockClicked())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void bucketEmpty(PlayerBucketEmptyEvent e) {
        if (protectedFrom(e.getPlayer(), e.getBlock()) || protectedFrom(e.getPlayer(), e.getBlockClicked()) ||
                protectedFrom(e.getPlayer(), e.getBlockClicked().getRelative(e.getBlockFace()))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void entityDamage(EntityDamageByEntityEvent e) {
        // PvP policy remains separate; protect non-player entities from outside players.
        if (e.getEntity() instanceof Player victim) {
            Entity attackerEntity = e.getDamager();
            if (attackerEntity instanceof org.bukkit.entity.Projectile projectile &&
                    projectile.getShooter() instanceof Player shooter) attackerEntity = shooter;
            // Spawn is a safe zone for everyone, including staff; bypass applies only to building.
            if (spawnProtected(victim.getLocation()) || spawnProtected(attackerEntity.getLocation()))
                e.setCancelled(true);
            return;
        }
        Entity attacker = e.getDamager();
        if (attacker instanceof org.bukkit.entity.Projectile projectile &&
                projectile.getShooter() instanceof Player shooter) attacker = shooter;
        if (attacker instanceof Player player && protectedFrom(player, e.getEntity().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void hangingBreak(HangingBreakByEntityEvent e) {
        if (e.getRemover() instanceof Player player) {
            if (protectedFrom(player, e.getEntity().getLocation())) e.setCancelled(true);
        } else if (lockdown() || spawnProtected(e.getEntity().getLocation()) ||
                db.isProtected(chunk(e.getEntity().getLocation()))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void vehicleDestroy(VehicleDestroyEvent e) {
        if (e.getAttacker() instanceof Player player && protectedFrom(player, e.getVehicle().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void entityChangesBlock(EntityChangeBlockEvent e) {
        if (worldProtected(e.getBlock()) &&
                (!(e.getEntity() instanceof Player player) || protectedFrom(player, e.getBlock()))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void doorBreak(EntityBreakDoorEvent e) { if (worldProtected(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explosion(EntityExplodeEvent e) { e.blockList().removeIf(this::worldProtected); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void blockExplosion(BlockExplodeEvent e) { e.blockList().removeIf(this::worldProtected); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void fireBurn(BlockBurnEvent e) { if (worldProtected(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void fireIgnite(BlockIgniteEvent e) { if (worldProtected(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void spread(BlockSpreadEvent e) { if (worldProtected(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void fluids(BlockFromToEvent e) { if (across(e.getBlock(), e.getToBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pistonExtend(BlockPistonExtendEvent e) {
        if (across(e.getBlock(), e.getBlock().getRelative(e.getDirection())) ||
                e.getBlocks().stream().anyMatch(b -> across(b, b.getRelative(e.getDirection())))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pistonRetract(BlockPistonRetractEvent e) {
        // The piston head crosses the claim boundary even with zero pulled blocks.
        // The getBlocks() list only describes sticky-piston payloads, not the head.
        if (across(e.getBlock(), e.getBlock().getRelative(e.getDirection())) ||
                e.getBlocks().stream().anyMatch(b -> across(b, b.getRelative(e.getDirection().getOppositeFace()))))
            e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void hopperTransfer(InventoryMoveItemEvent e) {
        if (lockdown()) { e.setCancelled(true); return; }
        Location from = e.getSource().getLocation(), to = e.getDestination().getLocation();
        // Inventory#getLocation() is nullable in the Paper API. Rather than
        // silently allowing unknown endpoints to bypass protected inventories,
        // block automated transfers when an endpoint cannot be located.
        if (across(from, to)) e.setCancelled(true);
    }
}
