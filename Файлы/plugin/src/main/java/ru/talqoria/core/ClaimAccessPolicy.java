package ru.talqoria.core;

/** Pure decision policy, independent of Paper events. Fail closed on damaged territory storage. */
public final class ClaimAccessPolicy {
    private ClaimAccessPolicy() { }
    public static boolean denyEdit(boolean databaseLocked, boolean staffBypass,
                                   boolean inSpawn, boolean membershipAllowsBuild) {
        if (databaseLocked) return true;
        return !staffBypass && (inSpawn || !membershipAllowsBuild);
    }
}
