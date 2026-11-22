package ru.talqoria.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Regression tests for physical cross-claim transfers. No server is needed. */
public final class ClaimAccessPolicyTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void unclaimedWildernessIsAccessibleAndTransferable() {
        assertFalse(ClaimAccessPolicy.denyEdit(false, false, false, true));
        assertFalse(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false, null, null));
    }

    @Test
    void editsInsideOtherTownsAndSpawnAreBlocked() {
        assertTrue(ClaimAccessPolicy.denyEdit(false, false, false, false));
        assertTrue(ClaimAccessPolicy.denyEdit(false, false, true, true));
        assertFalse(ClaimAccessPolicy.denyEdit(false, true, true, false));
        assertTrue(ClaimAccessPolicy.denyEdit(true, true, false, true));
    }

    @Test
    void pistonAndFluidMayMoveWithinOneClaim() {
        assertFalse(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false, A, A));
        assertFalse(ClaimAccessPolicy.denyBoundaryTransfer(false, true, true, A, A));
        assertFalse(ClaimAccessPolicy.denyBoundaryTransfer(false, true, true, null, null));
    }

    @Test
    void transfersEnteringOrLeavingClaimAreBlocked() {
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false, A, null));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false, null, A));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false, A, B));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false, B, A));
    }

    @Test
    void spawnBoundaryAlwaysBlocksMovement() {
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, true, false, null, null));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, false, true, A, A));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, false, true, null, null));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, true, false, A, A));
    }

    @Test
    void storageLockIsNeverBypassedByEqualClaimsOrStaff() {
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(true, false, false, null, null));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(true, false, false, A, A));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(true, true, true, A, A));
    }

    @Test
    void allPairsOfNamedClaimsHaveExpectedIsolation() {
        UUID[] towns = {null, A, B};
        for (UUID from : towns) for (UUID to : towns) {
            boolean expected = !java.util.Objects.equals(from, to);
            assertEquals(expected, ClaimAccessPolicy.denyBoundaryTransfer(false, false, false, from, to),
                "Boundary mismatch for " + from + " -> " + to);
        }
    }

    @Test
    void unlocatedInventoryTransfersFailClosed() {
        assertTrue(ClaimAccessPolicy.denyUnlocatedTransfer(false, false, true));
        assertTrue(ClaimAccessPolicy.denyUnlocatedTransfer(false, true, false));
        assertTrue(ClaimAccessPolicy.denyUnlocatedTransfer(false, false, false));
        assertTrue(ClaimAccessPolicy.denyUnlocatedTransfer(true, true, true));
        assertFalse(ClaimAccessPolicy.denyUnlocatedTransfer(false, true, true));
    }
    @Test
    void unclaimedVehiclesRemainDestructibleButProtectedVehiclesDoNot() {
        assertFalse(ClaimAccessPolicy.denyUnattributedVehicleDestruction(false, false, false));
        assertTrue(ClaimAccessPolicy.denyUnattributedVehicleDestruction(false, true, false));
        assertTrue(ClaimAccessPolicy.denyUnattributedVehicleDestruction(false, false, true));
        assertTrue(ClaimAccessPolicy.denyUnattributedVehicleDestruction(false, true, true));
        assertTrue(ClaimAccessPolicy.denyUnattributedVehicleDestruction(true, false, false));
    }

    @Test
    void dispenserFacingCellRespectsTheSameBoundaryAsPistonsAndFluids() {
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false, null, A));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false, A, null));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false, A, B));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(false, true, false, A, A));
        assertFalse(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false, A, A));
        assertFalse(ClaimAccessPolicy.denyBoundaryTransfer(false, false, false, null, null));
        assertTrue(ClaimAccessPolicy.denyBoundaryTransfer(true, false, false, A, A));
    }

}
