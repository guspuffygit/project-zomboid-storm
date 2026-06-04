package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.metrics.IsoObjectIdPoolMetrics;
import zombie.characters.IsoZombie;
import zombie.network.IsoObjectID;
import zombie.network.ServerMap;

/**
 * Pure logic behind {@link IsoZombieUpdateFixPatch}: enforce the invariant {@code onlineId != -1 ⇒
 * ServerMap.instance.zombieMap.get(onlineId) == this} every time an {@code IsoZombie.update()} call
 * returns on the server.
 *
 * <p>Vanilla {@code IsoZombie.updateInternal()} (around lines 3328-3329) re-allocates {@code
 * onlineId} when it has been reset to {@code -1} but never calls {@code zombieMap.put} for the new
 * id. The same bytecode path fires whenever {@code IsoZombie.load} drops a saved zombie into the
 * cell with the field's default {@code -1} value (the field is not serialized), so this is the
 * dominant orphan source — not a rare reanimation race. The downstream symptom is "mitosis":
 * server-side broadcasts iterate {@code cell.zombieList} (not {@code zombieMap}), so an orphan is
 * still broadcast under its new id; the client's {@code IDToZombieMap.get(packet.id)} miss path
 * spawns a brand-new client zombie via {@code VirtualZombieManager.createRealZombieAlways}.
 *
 * <p>{@link IsoZombieUpdateFixPatch} attaches an {@code @Advice.OnMethodExit} that calls {@link
 * #ensureMapEntry(Object)}. The check is idempotent — a zombie already correctly mapped under its
 * own id is a no-op, so re-running the advice on every tick costs only a map lookup.
 *
 * <p>Three outcomes per invocation, surfaced by {@link Action}:
 *
 * <ul>
 *   <li>{@link Action#NONE} — invariant already holds (or {@code onlineId == -1}).
 *   <li>{@link Action#MISSING_PUT} — id is set but the slot is empty; the zombie is put back into
 *       the map (heals the Bug 2 path and any pre-fix orphan).
 *   <li>{@link Action#COLLISION} — another zombie occupies the slot; {@code onlineId} is reset to
 *       {@code -1} so the next tick re-allocates via the patched {@code allocateID()} (which probes
 *       for a free slot, see {@link IsoObjectIDProbe}).
 * </ul>
 *
 * <p>The decision logic ({@link #decideAction}) is split out from the I/O ({@link #ensureMapEntry})
 * so it can be unit-tested without game classes on the classpath.
 */
public final class IsoZombieMapInvariant {

    /**
     * Matches {@code zombie.network.IsoObjectID.incorrect} / {@code IsoZombie.onlineId} default.
     */
    public static final short ID_INVALID = -1;

    /** Outcome of a single invariant check; surfaced for unit testing and metrics. */
    public enum Action {
        /** Invariant already holds, or {@code onlineId == -1}. */
        NONE,
        /** Id is set but the map slot is empty — the caller should {@code put(id, self)}. */
        MISSING_PUT,
        /**
         * Another zombie holds the slot — the caller should reset {@code onlineId} to {@code -1}.
         */
        COLLISION
    }

    private IsoZombieMapInvariant() {}

    /**
     * Pure decision: given an id, a "self" zombie reference, and whatever the map currently holds
     * under that id, what (if any) corrective action is required?
     *
     * @param onlineId the zombie's current {@code onlineId} field
     * @param self the zombie whose invariant is being checked
     * @param inMap the value currently mapped to {@code onlineId}, or {@code null} if the slot is
     *     empty
     * @return the action the driver should perform
     */
    public static Action decideAction(short onlineId, Object self, Object inMap) {
        if (onlineId == ID_INVALID) {
            return Action.NONE;
        }
        if (inMap == self) {
            return Action.NONE;
        }
        if (inMap == null) {
            return Action.MISSING_PUT;
        }
        return Action.COLLISION;
    }

    /**
     * Driver called from the {@code IsoZombie.update()} exit advice. Looks up the current map
     * entry, picks an action via {@link #decideAction}, and applies it.
     *
     * <p>{@code zombieRef} is typed {@code Object} so the inlined advice does not embed a checkcast
     * against {@code IsoZombie} into the patched method's bytecode (see the {@code
     * feedback_elided_cast_load} memory). The cast happens here, only on the first actual call,
     * after {@code IsoZombie} has already been loaded (we are inside its {@code update()} method).
     *
     * @param zombieRef the {@code IsoZombie} whose invariant is being enforced
     */
    public static void ensureMapEntry(Object zombieRef) {
        IsoZombie self = (IsoZombie) zombieRef;
        ServerMap sm = ServerMap.instance;
        if (sm == null) {
            return;
        }
        IsoObjectID<IsoZombie> map = sm.zombieMap;
        if (map == null) {
            return;
        }
        short id = self.onlineId;
        if (id == ID_INVALID) {
            return;
        }
        IsoZombie inMap = map.get(id);
        Action action = decideAction(id, self, inMap);
        switch (action) {
            case MISSING_PUT:
                map.put(id, self);
                IsoObjectIdPoolMetrics.recordZombieOrphanFix();
                break;
            case COLLISION:
                self.onlineId = ID_INVALID;
                IsoObjectIdPoolMetrics.recordZombieMapCollision();
                break;
            case NONE:
                break;
        }
    }
}
