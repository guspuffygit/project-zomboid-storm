package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests {@link IsoZombieMapInvariant#decideAction(short, Object, Object)} in isolation from
 * the ByteBuddy plumbing and the game's {@code IsoZombie} / {@code ServerMap} types. Drives the
 * decision logic with bare {@link Object} references — the helper doesn't care what they are, only
 * whether they match.
 *
 * <p>The exhaustive table:
 *
 * <table>
 *   <tr><th>onlineId</th><th>inMap</th><th>expected</th></tr>
 *   <tr><td>-1</td>     <td>anything</td><td>NONE</td></tr>
 *   <tr><td>≠ -1</td>   <td>null</td>    <td>MISSING_PUT</td></tr>
 *   <tr><td>≠ -1</td>   <td>self</td>    <td>NONE</td></tr>
 *   <tr><td>≠ -1</td>   <td>other</td>   <td>COLLISION</td></tr>
 * </table>
 */
class IsoZombieMapInvariantTest implements UnitTest {

    private static final Object SELF = new Object();
    private static final Object OTHER = new Object();

    @Test
    void invalidIdAlwaysReturnsNone() {
        // onlineId == -1 short-circuits regardless of map state — the field is in its "no id
        // allocated" state and the next update() tick will assign one.
        assertEquals(
                IsoZombieMapInvariant.Action.NONE,
                IsoZombieMapInvariant.decideAction(IsoZombieMapInvariant.ID_INVALID, SELF, null));
        assertEquals(
                IsoZombieMapInvariant.Action.NONE,
                IsoZombieMapInvariant.decideAction(IsoZombieMapInvariant.ID_INVALID, SELF, SELF));
        assertEquals(
                IsoZombieMapInvariant.Action.NONE,
                IsoZombieMapInvariant.decideAction(IsoZombieMapInvariant.ID_INVALID, SELF, OTHER));
    }

    @Test
    void emptySlotIsMissingPut() {
        // The Bug 2 / chunk-load path: zombie was assigned an id but never inserted into the map.
        // Heal by putting (id, self).
        assertEquals(
                IsoZombieMapInvariant.Action.MISSING_PUT,
                IsoZombieMapInvariant.decideAction((short) 100, SELF, null));
        assertEquals(
                IsoZombieMapInvariant.Action.MISSING_PUT,
                IsoZombieMapInvariant.decideAction(Short.MIN_VALUE, SELF, null));
        assertEquals(
                IsoZombieMapInvariant.Action.MISSING_PUT,
                IsoZombieMapInvariant.decideAction(Short.MAX_VALUE, SELF, null));
    }

    @Test
    void selfAlreadyHoldsTheSlot() {
        // Steady-state: zombie was properly inserted on a previous tick. Subsequent ticks are
        // no-ops (one map lookup per zombie per tick).
        assertEquals(
                IsoZombieMapInvariant.Action.NONE,
                IsoZombieMapInvariant.decideAction((short) 100, SELF, SELF));
    }

    @Test
    void otherZombieInTheSlotIsCollision() {
        // Recovery path: another zombie is mapped under this id. We can't evict the rightful
        // holder, so the driver clears self.onlineId and lets next-tick's allocate produce a new
        // id (the probe-for-free allocate guarantees the new id will be unoccupied).
        assertEquals(
                IsoZombieMapInvariant.Action.COLLISION,
                IsoZombieMapInvariant.decideAction((short) 100, SELF, OTHER));
    }

    @Test
    void identityMatch_notEquality() {
        // The helper uses reference equality on purpose — two distinct Object()s with no equals()
        // override are distinct identities. This guards against a future "equals" creep that would
        // treat two zombies with the same state as the same holder.
        Object selfClone = new Object();
        assertEquals(
                IsoZombieMapInvariant.Action.COLLISION,
                IsoZombieMapInvariant.decideAction((short) 100, SELF, selfClone));
    }
}
