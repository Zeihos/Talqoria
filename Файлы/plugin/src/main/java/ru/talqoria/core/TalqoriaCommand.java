package ru.talqoria.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class TalqoriaCommand implements CommandExecutor, TabCompleter {
    private final TalqoriaCorePlugin plugin;
    private final WalletStore wallets;
    private final TerritoryCommand territory;
    private final QuestController quests;
    private final StaffCommand staff;
    private final ShopCommand shop;
    private final JourneyCommand journey;

    public TalqoriaCommand(TalqoriaCorePlugin plugin, WalletStore wallets, TerritoryStore territories, QuestController quests) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.territory = territories == null ? null : new TerritoryCommand(plugin, territories);
        this.quests = quests;
        this.staff = new StaffCommand(plugin, wallets, territories);
        this.shop = new ShopCommand(plugin, wallets);
        this.journey = new JourneyCommand(plugin, wallets, territories);
    }

    private void say(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.DARK_GREEN + "[TALQORIA] " + ChatColor.GRAY + message);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        long started = System.nanoTime();
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            return executeTracked(sender, action, args);
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000;
            long warn = Math.max(10, plugin.getConfig().getLong("diagnostics.slow-command-ms", 100));
            if (millis >= warn) plugin.getLogger().warning("SLOW_COMMAND tq " + action + " " + millis + "ms (player=" + sender.getName() + ")");
        }
    }

    private boolean executeTracked(CommandSender sender, String action, String[] args) {
        if (action.equals("help")) {
            say(sender, "Команды: /tq profile, /tq balance, /tq daily, /tq pay <ник> <сумма>, /tq spawn");
            say(sender, "Поселения: /tq town help, /tq claim, /tq unclaim, /tq claims, /tq trust, /tq untrust");
            say(sender, "Задания: /tq quest, /tq quest claim <miner|lumber|hunter>");
            say(sender, "Путь новичка: /tq journey | claim <explore|settle|protect>");
            say(sender, "Торговля: /tq shop | buy|sell <товар> <1..64>");
            if (sender.hasPermission("talqoria.admin")) say(sender, "Администратор: /tq setspawn");
            if (sender.hasPermission("talqoria.staff.status")) say(sender, "Команда: /tq staff status | inspect <ник> | market | audit");
            return true;
        }
        if (action.equals("staff")) { staff.execute(sender, args); return true; }
        if (!(sender instanceof Player player)) {
            say(sender, "Эта команда доступна только игроку в Minecraft.");
            return true;
        }
        if (action.equals("shop")) { shop.execute(player, args); return true; }
        if (action.equals("journey")) { journey.execute(player, args); return true; }
        if (action.equals("quest")) {
            if (quests == null || plugin.questsLocked()) say(sender, "Задания временно недоступны из-за ошибки хранения.");
            else quests.execute(player, args);
            return true;
        }
        if (List.of("town", "claim", "unclaim", "claims", "trust", "untrust").contains(action)) {
            if (plugin.territoryLocked() || territory == null) {
                say(sender, "Территории временно заблокированы из-за ошибки сохранения. Обратись к администрации.");
            } else territory.execute(player, args);
            return true;
        }
        switch (action) {
            case "profile", "balance" -> {
                if (plugin.walletLocked()) { say(sender, "Кошельки заблокированы: ошибка сохранения."); return true; }
                say(sender, "Профиль " + player.getName() + " · баланс: " + wallets.balance(player.getUniqueId()) + " монет.");
            }
            case "daily" -> {
                if (plugin.walletLocked()) { say(sender, "Кошельки заблокированы: ошибка сохранения."); return true; }
                WalletStore.DailyResult result = wallets.claimDailyAndSave(player.getUniqueId());
                switch (result.status()) {
                    case OK -> say(sender, "Ежедневная награда: +" + result.amount() + " монет!");
                    case ALREADY -> say(sender, "Награда уже получена сегодня (сброс в 00:00 UTC).");
                    case OVERFLOW -> say(sender, "Баланс достиг максимума.");
                    case IO_ERROR -> {
                        plugin.lockWallets("Daily reward write failed; investigate storage");
                        say(sender, "Ошибка записи кошелька. Экономика заблокирована.");
                    }
                }
            }
            case "pay" -> {
                if (plugin.walletLocked()) { say(sender, "Кошельки заблокированы: ошибка сохранения."); return true; }
                if (args.length != 3) {
                    say(sender, "Использование: /tq pay <ник_онлайн> <целое_число>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    say(sender, "Игрок должен быть онлайн.");
                    return true;
                }
                long amount;
                try { amount = Long.parseLong(args[2]); }
                catch (NumberFormatException ignored) {
                    say(sender, "Сумма должна быть положительным целым числом.");
                    return true;
                }
                WalletStore.Transfer result = wallets.transferAndSave(player.getUniqueId(), target.getUniqueId(), amount);
                switch (result) {
                    case OK -> {
                        say(sender, "Переведено " + amount + " монет игроку " + target.getName() + ".");
                        say(target, "Получено " + amount + " монет от " + player.getName() + ".");
                    }
                    case INSUFFICIENT -> say(sender, "Недостаточно монет.");
                    case INVALID -> say(sender, "Некорректная сумма, превышен лимит или перевод самому себе.");
                    case OVERFLOW -> say(sender, "Баланс получателя достиг максимума.");
                    case IO_ERROR -> {
                        plugin.lockWallets("Player transfer write failed; investigate storage");
                        say(sender, "Ошибка сохранения перевода. Экономика заблокирована.");
                    }
                }
            }
            case "spawn" -> {
                Location spawn = plugin.readSpawn();
                if (spawn == null) say(sender, "Точка спавна не определена.");
                else {
                    player.teleport(spawn);
                    say(sender, "Телепортация на спавн.");
                }
            }
            case "setspawn" -> {
                if (!sender.hasPermission("talqoria.admin")) {
                    say(sender, "Недостаточно прав.");
                    return true;
                }
                Location position = player.getLocation();
                if (!plugin.auditAction(player.getUniqueId().toString(), "SPAWN_CHANGE",
                        position.getWorld().getUID().toString(),
                        position.getBlockX() + "," + position.getBlockY() + "," + position.getBlockZ())) {
                    say(sender, "Аудит недоступен: изменение спавна запрещено.");
                    return true;
                }
                plugin.setSpawn(position);
                say(sender, "Спавн установлен и сохранён.");
            }
            default -> say(sender, "Неизвестная команда. Используй /tq help.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> commands = new ArrayList<>(Arrays.asList("help", "profile", "balance", "daily", "pay", "spawn", "town", "claim", "unclaim", "claims", "trust", "untrust", "quest", "shop", "journey"));
            if (sender.hasPermission("talqoria.staff.status")) commands.add("staff");
            if (sender.hasPermission("talqoria.admin")) commands.add("setspawn");
            return commands.stream().filter(x -> x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("quest"))
            return List.of("list", "claim").stream().filter(x -> x.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("quest") && args[1].equalsIgnoreCase("claim"))
            return List.of("miner", "lumber", "hunter").stream().filter(x -> x.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("shop"))
            return List.of("list", "buy", "sell").stream().filter(x -> x.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("shop"))
            return MarketCatalog.OFFERS.stream().map(MarketCatalog.Offer::id)
                    .filter(x -> x.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("journey"))
            return List.of("list", "claim").stream().filter(x -> x.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("journey") && args[1].equalsIgnoreCase("claim"))
            return JourneyRules.GOALS.stream().map(JourneyRules.Goal::id)
                    .filter(x -> x.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("town")) {
            return List.of("help", "create", "info", "invite", "accept", "leave", "kick", "promote", "demote", "disband")
                .stream().filter(x -> x.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pay")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
