package ru.talqoria.core;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/** Starter path: payouts are idempotent receipts inside the wallet snapshot. */
final class JourneyCommand {
    private final TalqoriaCorePlugin plugin;
    private final WalletStore wallets;
    private final TerritoryStore territories;
    JourneyCommand(TalqoriaCorePlugin plugin, WalletStore wallets, TerritoryStore territories) {
        this.plugin = plugin; this.wallets = wallets; this.territories = territories;
    }
    private void say(Player p, String msg) { p.sendMessage("§2[TALQORIA] §7" + msg); }
    private String receipt(UUID user, String goal) { return "journey_" + goal + "_" + user; }
    private boolean ready(Player p, String id) {
        Location spawn = plugin.readSpawn();
        Location here = p.getLocation();
        boolean sameWorld = spawn != null && spawn.getWorld() != null && spawn.getWorld().equals(here.getWorld());
        double distance = sameWorld ? Math.hypot(spawn.getX() - here.getX(), spawn.getZ() - here.getZ()) : 0;
        TerritoryStore.Town town = territories == null ? null : territories.townFor(p.getUniqueId());
        return JourneyRules.eligible(id, p.getGameMode() == GameMode.SURVIVAL, sameWorld,
                distance, town != null, town != null && territories.claimCount(town) > 0);
    }
    void execute(Player p, String[] args) {
        if (plugin.walletLocked() || plugin.territoryLocked()) {
            say(p, "Путь новичка временно недоступен из-за ошибки хранения."); return;
        }
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) {
            say(p, "Путь новичка — три разовые награды:");
            for (JourneyRules.Goal goal : JourneyRules.GOALS) {
                boolean claimed = wallets.hasReward(p.getUniqueId(), receipt(p.getUniqueId(), goal.id()));
                say(p, goal.id() + " · " + goal.title() + " · " + goal.coins() + " монет · "
                        + (claimed ? "получено" : ready(p, goal.id()) ? "можно получить" : "не выполнено"));
            }
            say(p, "Получить награду: /tq journey claim <explore|settle|protect>");
            return;
        }
        if (args.length != 3 || !args[1].equalsIgnoreCase("claim")) {
            say(p, "Использование: /tq journey [list | claim <цель>]"); return;
        }
        JourneyRules.Goal goal = JourneyRules.find(args[2]);
        if (goal == null) { say(p, "Неизвестная цель. /tq journey"); return; }
        UUID uuid = p.getUniqueId();
        String receipt = receipt(uuid, goal.id());
        if (wallets.hasReward(uuid, receipt)) { say(p, "Эта награда уже получена."); return; }
        if (!ready(p, goal.id())) { say(p, "Цель пока не выполнена или ты не в режиме Выживание."); return; }
        WalletStore.RewardGrant result = wallets.creditQuestOnce(uuid, receipt, goal.coins());
        if (result == WalletStore.RewardGrant.IO_ERROR) plugin.lockWallets("Starter journey reward save failed");
        say(p, switch (result) {
            case OK -> "Путь новичка: +" + goal.coins() + " монет!";
            case ALREADY -> "Эта награда уже получена.";
            case IO_ERROR -> "Ошибка сохранения. Экономика заблокирована.";
            default -> "Не удалось выдать награду: " + result;
        });
    }
}
