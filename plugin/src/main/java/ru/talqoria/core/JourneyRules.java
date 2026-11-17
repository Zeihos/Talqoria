package ru.talqoria.core;

import java.util.List;

/** One-time starter goals. Receipt permanence is provided by WalletStore, not this class. */
public final class JourneyRules {
    private JourneyRules() { }
    public record Goal(String id, String title, long coins) { }
    public static final List<Goal> GOALS = List.of(
            new Goal("explore", "Отойди от спавна хотя бы на 256 блоков", 60),
            new Goal("settle", "Вступи в поселение или создай своё", 90),
            new Goal("protect", "Помоги поселению получить первый приват", 130));
    public static Goal find(String id) {
        if (id == null) return null;
        for (Goal g : GOALS) if (g.id().equalsIgnoreCase(id)) return g;
        return null;
    }
    /** Whether the milestone is fulfilled NOW. Never trust a client-supplied result. */
    public static boolean eligible(String id, boolean survival, boolean sameWorld,
                                   double horizontalDistance, boolean inTown, boolean hasTownClaim) {
        if (!survival || find(id) == null) return false;
        return switch (id.toLowerCase(java.util.Locale.ROOT)) {
            case "explore" -> sameWorld && Double.isFinite(horizontalDistance) && horizontalDistance >= 256.0;
            case "settle" -> inTown;
            case "protect" -> inTown && hasTownClaim;
            default -> false;
        };
    }
}
