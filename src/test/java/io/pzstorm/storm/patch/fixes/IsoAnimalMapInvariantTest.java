package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests {@link IsoAnimalMapInvariant#decideAction(short, Object, Object)} in isolation from
 * the ByteBuddy plumbing and the game's {@code IsoAnimal} / {@code AnimalInstanceManager} types.
 * Drives the decision logic with bare {@link Object} references — the helper doesn't care what they
 * are, only whether they match.
 *
 * <p>The exhaustive table:
 *
 * <table>
 *   <tr><th>onlineId</th><th>inMap</th><th>expected</th></tr>
 *   <tr><td>anything</td> <td>self</td>  <td>NONE</td></tr>
 *   <tr><td>1 or -1</td>  <td>≠ self</td><td>COLLISION</td></tr>
 *   <tr><td>allocated</td><td>null</td>  <td>MISSING_PUT</td></tr>
 *   <tr><td>allocated</td><td>other</td> <td>COLLISION</td></tr>
 * </table>
 */
class IsoAnimalMapInvariantTest implements UnitTest {

    private static final Object SELF = new Object();
    private static final Object OTHER = new Object();

    @Test
    void selfAlreadyHoldsTheSlotIsAlwaysNone() {
        // The steady state: one map lookup per tick and nothing else. Holds even for the
        // unassigned/invalid sentinels, which are legal slots once actually allocated.
        assertEquals(
                IsoAnimalMapInvariant.Action.NONE,
                IsoAnimalMapInvariant.decideAction((short) 22217, SELF, SELF));
        assertEquals(
                IsoAnimalMapInvariant.Action.NONE,
                IsoAnimalMapInvariant.decideAction(
                        IsoAnimalMapInvariant.ID_UNASSIGNED, SELF, SELF));
        assertEquals(
                IsoAnimalMapInvariant.Action.NONE,
                IsoAnimalMapInvariant.decideAction(IsoAnimalMapInvariant.ID_INVALID, SELF, SELF));
    }

    @Test
    void emptySlotWithAllocatedIdIsMissingPut() {
        // virtualizeAnimal -> IsoAnimal.delete() freed the slot, then fromWorker re-realized the
        // same instance without re-registering it. Heal in place so the id stays stable.
        assertEquals(
                IsoAnimalMapInvariant.Action.MISSING_PUT,
                IsoAnimalMapInvariant.decideAction((short) 22217, SELF, null));
        assertEquals(
                IsoAnimalMapInvariant.Action.MISSING_PUT,
                IsoAnimalMapInvariant.decideAction(Short.MIN_VALUE, SELF, null));
        assertEquals(
                IsoAnimalMapInvariant.Action.MISSING_PUT,
                IsoAnimalMapInvariant.decideAction(Short.MAX_VALUE, SELF, null));
    }

    @Test
    void anotherAnimalHoldsTheSlotIsCollision() {
        assertEquals(
                IsoAnimalMapInvariant.Action.COLLISION,
                IsoAnimalMapInvariant.decideAction((short) 22217, SELF, OTHER));
    }

    @Test
    void sentinelIdsNeverClaimTheirOwnSlot() {
        // onlineId 1 is the IsoPlayer field default — an animal that init() never registered. It
        // must allocate rather than squat on slot 1, which allocateID() may hand to someone else.
        assertEquals(
                IsoAnimalMapInvariant.Action.COLLISION,
                IsoAnimalMapInvariant.decideAction(
                        IsoAnimalMapInvariant.ID_UNASSIGNED, SELF, null));
        assertEquals(
                IsoAnimalMapInvariant.Action.COLLISION,
                IsoAnimalMapInvariant.decideAction(
                        IsoAnimalMapInvariant.ID_UNASSIGNED, SELF, OTHER));
        assertEquals(
                IsoAnimalMapInvariant.Action.COLLISION,
                IsoAnimalMapInvariant.decideAction(IsoAnimalMapInvariant.ID_INVALID, SELF, null));
    }
}
