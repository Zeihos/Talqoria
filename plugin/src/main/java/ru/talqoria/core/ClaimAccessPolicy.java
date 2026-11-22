package ru.talqoria.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Paper-independent access and boundary policies.
 *
 * Claim ownership is represented by a town ID, not by a player ID.
 * A non-null claim represents protected territory; null means unclaimed.
 */
public final class ClaimAccessPolicy {
    private ClaimAccessPolicy() { }

    /** Unknown damage sources cannot destroy protected vehicles. */
    public static boolean denyUnattributedVehicleDestruction(boolean databaseLocked,
                                                             boolean inSpawn, boolean inClaim) {
        return databaseLocked || inSpawn || inClaim;
    }

    public static boolean denyEdit(boolean databaseLocked, boolean staffBypass,
                                   boolean inSpawn, boolean membershipAllowsBuild) {
        if (databaseLocked) return true;
        return !staffBypass && (inSpawn || !membershipAllowsBuild);
    }

    /** A physical automation with an unknown endpoint cannot be authorized safely. */
    public static boolean denyUnlocatedTransfer(boolean databaseLocked,
                                                boolean sourceLocated, boolean destinationLocated) {
        return databaseLocked || !sourceLocated || !destinationLocated;
    }

    /**
     * True when a physical transfer (fluid, piston, hopper, dispenser)
     * crosses the spawn boundary or a different town's claim.
     */
    public static boolean denyBoundaryTransfer(boolean databaseLocked,
                                               boolean sourceSpawn, boolean destinationSpawn,
                                               UUID sourceTown, UUID destinationTown) {
        if (databaseLocked) return true;
        if (sourceSpawn != destinationSpawn) return true;
        return !Objects.equals(sourceTown, destinationTown)
            && (sourceTown != null || destinationTown != null);
    }
}
