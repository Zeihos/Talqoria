package ru.talqoria.core;

import java.util.List;
import java.util.Locale;

/** Fixed server shop. No player-owned listings, real-money payments or arbitrary NBT. */
public final class MarketCatalog {
    private MarketCatalog() { }

    /** buyPrice is what the player pays; sellPrice is what the player receives. */
    public record Offer(String id, String material, String title, long buyPrice, long sellPrice) {
        public Offer {
            if (id == null || !id.matches("[a-z0-9_]{2,32}") || material == null || title == null
                    || title.isBlank() || buyPrice <= 0 || sellPrice < 0 || sellPrice >= buyPrice)
                throw new IllegalArgumentException("Invalid market offer");
        }
    }
    public enum QuoteError { NONE, UNKNOWN_ITEM, INVALID_QUANTITY, NOT_FOR_SALE, PRICE_OVERFLOW }
    public record Quote(Offer offer, int quantity, long total, QuoteError error) {
        public boolean valid() { return error == QuoteError.NONE; }
    }
    public static final int MAX_QUANTITY = 64;
    public static final List<Offer> OFFERS = List.of(
            new Offer("cobble", "COBBLESTONE", "Булыжник", 3, 1),
            new Offer("oak", "OAK_LOG", "Дубовые брёвна", 9, 3),
            new Offer("bread", "BREAD", "Хлеб", 14, 5),
            new Offer("iron", "IRON_INGOT", "Железный слиток", 32, 12),
            new Offer("torch", "TORCH", "Факел", 4, 1));

    public static Offer find(String id) {
        if (id == null) return null;
        for (Offer offer : OFFERS) if (offer.id().equals(id.toLowerCase(Locale.ROOT))) return offer;
        return null;
    }
    public static Quote quote(String id, int quantity, boolean buying) {
        Offer offer = find(id);
        if (offer == null) return new Quote(null, quantity, 0, QuoteError.UNKNOWN_ITEM);
        if (quantity < 1 || quantity > MAX_QUANTITY) return new Quote(offer, quantity, 0, QuoteError.INVALID_QUANTITY);
        long unit = buying ? offer.buyPrice() : offer.sellPrice();
        if (unit <= 0) return new Quote(offer, quantity, 0, QuoteError.NOT_FOR_SALE);
        try { return new Quote(offer, quantity, Math.multiplyExact(unit, quantity), QuoteError.NONE); }
        catch (ArithmeticException over) { return new Quote(offer, quantity, 0, QuoteError.PRICE_OVERFLOW); }
    }
}
