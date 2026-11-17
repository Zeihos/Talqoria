package ru.talqoria.core;

import java.io.IOException;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Permission-gated read-only diagnostics, with an append-only inspection audit trail. */
final class StaffCommand {
    private final TalqoriaCorePlugin plugin;
    private final WalletStore wallets;
    private final TerritoryStore towns;
    StaffCommand(TalqoriaCorePlugin plugin, WalletStore wallets, TerritoryStore towns) {
        this.plugin = plugin; this.wallets = wallets; this.towns = towns;
    }
    private void say(CommandSender target, String message) { target.sendMessage("§2[TALQORIA] §7" + message); }
    private String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "CONSOLE";
    }
    void execute(CommandSender sender, String[] args) {
        if (args.length <= 1 || args[1].equalsIgnoreCase("status")) {
            if (!sender.hasPermission("talqoria.staff.status")) { say(sender, "Недостаточно прав."); return; }
            say(sender, "Игроков онлайн: " + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers());
            say(sender, "Журнал персонала: " + (plugin.auditLocked() ? "ЗАБЛОКИРОВАН" :
                    "проверен · записей " + plugin.staffAudit().count()));
            say(sender, "Хранилище кошельков: " + (plugin.walletLocked() ? "ЗАБЛОКИРОВАНО" : "активно"));
            say(sender, "Приваты: " + (plugin.territoryLocked() ? "ЗАБЛОКИРОВАНЫ" : "активны"));
            say(sender, "Задания: " + (plugin.questsLocked() ? "ЗАБЛОКИРОВАНЫ" : "активны"));
            say(sender, "Рынок: " + (!plugin.marketEnabled() ? "ОТКЛЮЧЕН В КОНФИГУРАЦИИ" :
                (plugin.marketLocked() ? "ЗАБЛОКИРОВАН" : "тестовый режим")));
            return;
        }
        if (args[1].equalsIgnoreCase("audit")) {
            if (!sender.hasPermission("talqoria.staff.audit")) { say(sender, "Недостаточно прав."); return; }
            if (plugin.auditLocked()) { say(sender, "Журнал персонала повреждён или недоступен. Проверь диск."); return; }
            if (args.length > 3 || (args.length == 3 && !"tail".equalsIgnoreCase(args[2]))) {
                say(sender, "Использование: /tq staff audit [tail]"); return;
            }
            say(sender, "Последние записи аудита. Всего: " + plugin.staffAudit().count());
            for (AuditLedger.Entry e : plugin.staffAudit().tail(5)) {
                say(sender, "#" + e.number() + " " + e.when() + " " + e.action() +
                    " actor=" + e.actor() + " target=" + e.target());
            }
            return;
        }
        if (args[1].equalsIgnoreCase("market")) {
            if (!sender.hasPermission("talqoria.staff.market")) { say(sender, "Недостаточно прав."); return; }
            MarketJournal journal = plugin.marketJournal();
            if (journal == null) { say(sender, "Журнал повреждён или не загружен: проверь логи сервера."); return; }
            MarketJournal.Pending pending = journal.pending();
            if (pending == null) { say(sender, "Незавершённых сделок нет. Рынок: " +
                    (plugin.marketEnabled() ? "тестовый" : "отключён")); return; }
            if (!plugin.auditAction(actor(sender), "MARKET_INSPECT", pending.id().toString(),
                    "pending trade inspection")) {
                say(sender, "Аудит недоступен: доступ к деталям закрыт."); return;
            }
            say(sender, "НЕЗАВЕРШЕННАЯ СДЕЛКА " + pending.id() + " игрок " + pending.player()
                + " · " + pending.side() + " " + pending.item() + " x" + pending.quantity()
                + " · монеты " + pending.amount() + " · " + pending.openedAt());
            if (args.length == 5 && args[2].equalsIgnoreCase("resolve") && args[4].equals("CONFIRM")) {
                if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
                    say(sender, "Подтверждение только из консоли сервера."); return;
                }
                if (!pending.id().toString().equals(args[3])) { say(sender, "UUID сделки не совпадает."); return; }
                if (!plugin.auditAction("CONSOLE", "MARKET_RESOLVE", pending.id().toString(),
                        "MANUALLY_RECONCILED after operator review")) {
                    say(sender, "Аудит недоступен: операция запрещена."); return;
                }
                try {
                    journal.complete(pending.id(), "MANUALLY_RECONCILED");
                    say(sender, "Сделка отмечена как сверенная. Для снятия блокировки перезапусти сервер.");
                } catch (IOException ex) { say(sender, "Ошибка записи аудита/журнала: " + ex.getMessage()); }
            } else say(sender, "Сверь игроков, инвентарь и баланс. Затем из консоли: /tq staff market resolve <UUID> CONFIRM");
            return;
        }
        if (args[1].equalsIgnoreCase("inspect") && args.length == 3) {
            if (!sender.hasPermission("talqoria.staff.inspect")) { say(sender, "Недостаточно прав."); return; }
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) { say(sender, "Для проверки игрок должен быть онлайн."); return; }
            if (!plugin.auditAction(actor(sender), "INSPECT", target.getUniqueId().toString(),
                    "player=" + target.getName())) {
                say(sender, "Не удалось записать аудит. Доступ закрыт."); return;
            }
            say(sender, "Игрок: " + target.getName() + " · UUID " + target.getUniqueId());
            say(sender, "Баланс: " + (plugin.walletLocked() ? "недоступен" : wallets.balance(target.getUniqueId())));
            if (towns != null && !plugin.territoryLocked()) {
                TerritoryStore.Town town = towns.townFor(target.getUniqueId());
                say(sender, "Поселение: " + (town == null ? "нет" : town.name() + " (ур. " + town.level() + ")"));
            }
            return;
        }
        say(sender, "Использование: /tq staff status | inspect <ник_онлайн> | market [resolve <UUID> CONFIRM] | audit [tail]");
    }
}
