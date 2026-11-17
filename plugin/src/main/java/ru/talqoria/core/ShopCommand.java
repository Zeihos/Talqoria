package ru.talqoria.core;

import java.util.Map;
import java.io.IOException;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Server-run fixed price shop. Alpha: wallet and inventory are not one durable transaction. */
final class ShopCommand {
    private final TalqoriaCorePlugin plugin;
    private final WalletStore wallets;
    ShopCommand(TalqoriaCorePlugin plugin, WalletStore wallets) { this.plugin = plugin; this.wallets = wallets; }
    private void say(Player p, String msg) { p.sendMessage("§2[TALQORIA] §7" + msg); }

    void execute(Player player, String[] args) {
        if (plugin.walletLocked()) { say(player, "Экономика заблокирована из-за ошибки хранения."); return; }
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) {
            say(player, "Рынок TALQORIA (за 1 предмет):");
            for (MarketCatalog.Offer offer : MarketCatalog.OFFERS)
                say(player, offer.id() + " · " + offer.title() + " · купить: " + offer.buyPrice()
                        + " · продать: " + offer.sellPrice());
            say(player, "Использование: /tq shop buy|sell <id> <1..64>. Только Выживание.");
            return;
        }
        if (!plugin.marketEnabled() || plugin.marketLocked()) {
            say(player, "Сделки отключены: тестовый режим или требуется сверка журнала торговли."); return;
        }
        if (player.getGameMode() != GameMode.SURVIVAL) {
            say(player, "Рынок работает только в режиме Выживание."); return;
        }
        if (args.length != 4 || (!args[1].equalsIgnoreCase("buy") && !args[1].equalsIgnoreCase("sell"))) {
            say(player, "Использование: /tq shop buy|sell <id> <количество>"); return;
        }
        final int qty;
        try { qty = Integer.parseInt(args[3]); }
        catch (NumberFormatException bad) { say(player, "Количество: целое число от 1 до 64."); return; }
        boolean buying = args[1].equalsIgnoreCase("buy");
        MarketCatalog.Quote quote = MarketCatalog.quote(args[2], qty, buying);
        if (!quote.valid()) { say(player, "Неверный товар, количество или цена: " + quote.error()); return; }
        Material material = Material.matchMaterial(quote.offer().material());
        if (material == null || !material.isItem()) {
            plugin.getLogger().severe("Shop catalog contains unsupported Minecraft material: " + quote.offer().material());
            say(player, "Товар временно недоступен."); return;
        }
        Inventory inventory = player.getInventory();
        ItemStack item = new ItemStack(material, qty);
        if (qty > item.getMaxStackSize()) { say(player, "Превышен размер стопки предмета."); return; }
        // Preflight before creating an outstanding journal entry.
        if (buying && !fits(inventory, item)) { say(player, "Недостаточно места в инвентаре."); return; }
        if (buying && wallets.balance(player.getUniqueId()) < quote.total()) { say(player, "Недостаточно монет."); return; }
        if (!buying && countPlain(inventory, item) < qty) {
            say(player, "Недостаточно обычных предметов без метаданных."); return;
        }
        MarketJournal.Pending pending;
        try {
            pending = plugin.marketJournal().begin(player.getUniqueId(),
                buying ? "BUY" : "SELL", quote.offer().id(), qty, quote.total());
        } catch (IOException | RuntimeException ex) {
            plugin.lockMarket("Cannot persist new trade intent: " + ex.getMessage());
            say(player, "Журнал торговли недоступен; сделка отклонена.");
            return;
        }
        boolean completed;
        try { completed = buying ? buy(player, inventory, item, quote) : sell(player, inventory, item, quote); }
        catch (RuntimeException failure) {
            plugin.lockMarket("Trade failed mid-operation, pending=" + pending.id() + ": " + failure);
            say(player, "Сбой операции. Нужна проверка администратора. Не повторяй сделку.");
            return;
        }
        if (!completed) {
            plugin.lockMarket("Unresolved trade " + pending.id());
            say(player, "Операция требует проверки; рынок заблокирован.");
            return;
        }
        try { plugin.marketJournal().complete(pending.id(), "COMPLETED"); }
        catch (IOException | RuntimeException failure) {
            plugin.lockMarket("Cannot close trade journal " + pending.id() + ": " + failure);
            say(player, "Операция завершена, но журнал не закрыт. Сообщи администрации.");
        }
    }
    private static int countPlain(Inventory inv, ItemStack item) {
        int n = 0;
        for (ItemStack stack : inv.getStorageContents())
            if (stack != null && stack.isSimilar(item)) n += stack.getAmount();
        return n;
    }
    private static boolean fits(Inventory inv, ItemStack item) {
        int free = 0;
        for (ItemStack stack : inv.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) free += item.getMaxStackSize();
            else if (stack.isSimilar(item)) free += Math.max(0, stack.getMaxStackSize() - stack.getAmount());
            if (free >= item.getAmount()) return true;
        }
        return false;
    }
    private static void removePlain(Inventory inv, ItemStack item) {
        int remaining = item.getAmount();
        ItemStack[] contents = inv.getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || !stack.isSimilar(item)) continue;
            int take = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            contents[slot] = stack.getAmount() == 0 ? null : stack;
            remaining -= take;
        }
        if (remaining != 0) throw new IllegalStateException("Inventory item count changed during shop operation");
        inv.setStorageContents(contents);
    }
    private boolean buy(Player p, Inventory inv, ItemStack item, MarketCatalog.Quote q) {
        if (!fits(inv, item)) { say(p, "Недостаточно места в инвентаре."); return false; }
        WalletStore.MarketChange charged = wallets.marketChange(p.getUniqueId(), -q.total());
        if (charged != WalletStore.MarketChange.OK) {
            failWallet(p, charged); return false;
        }
        // Coins are committed before giving items: prevents free items if wallet save fails.
        try {
            Map<Integer, ItemStack> leftovers = inv.addItem(item.clone());
            if (!leftovers.isEmpty()) {
                int unpaid = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
                WalletStore.MarketChange refund = wallets.marketChange(p.getUniqueId(),
                        Math.multiplyExact(unpaid, q.offer().buyPrice()));
                if (refund != WalletStore.MarketChange.OK) {
                    plugin.lockWallets("Market buy partial delivery refund failed");
                    say(p, "Критическая ошибка возврата монет. Обратись к администрации."); return false;
                }
                say(p, "Выдано " + (item.getAmount() - unpaid) + " шт., остаток монет возвращён.");
            } else say(p, "Куплено " + item.getAmount() + " шт. «" + q.offer().title() + "» за " + q.total() + " монет.");
            return true;
        } catch (RuntimeException failure) {
            plugin.lockWallets("Market buy item delivery failed after wallet charge: " + failure.getMessage());
            say(p, "Сбой выдачи предметов. НЕ повторяй операцию, сообщи администратору.");
            return false;
        }
    }
    private boolean sell(Player p, Inventory inv, ItemStack item, MarketCatalog.Quote q) {
        if (countPlain(inv, item) < item.getAmount()) {
            say(p, "Недостаточно обычных (без метаданных) предметов для продажи."); return false;
        }
        removePlain(inv, item);
        WalletStore.MarketChange credited = wallets.marketChange(p.getUniqueId(), q.total());
        if (credited == WalletStore.MarketChange.OK) {
            say(p, "Продано " + item.getAmount() + " шт. «" + q.offer().title() + "» за " + q.total() + " монет.");
            return true;
        }
        // On failed wallet save, restore removed items to inventory synchronously.
        Map<Integer, ItemStack> leftover = inv.addItem(item.clone());
        if (!leftover.isEmpty()) plugin.lockWallets("Market sell refund could not restore items");
        failWallet(p, credited);
        return false;
    }
    private void failWallet(Player p, WalletStore.MarketChange result) {
        if (result == WalletStore.MarketChange.IO_ERROR) plugin.lockWallets("Market coin transaction failed to save");
        say(p, switch (result) {
            case INSUFFICIENT -> "Недостаточно монет.";
            case OVERFLOW -> "Баланс достиг максимума.";
            case IO_ERROR -> "Ошибка записи кошелька. Экономика заблокирована.";
            default -> "Торговая операция отклонена.";
        });
    }
}
