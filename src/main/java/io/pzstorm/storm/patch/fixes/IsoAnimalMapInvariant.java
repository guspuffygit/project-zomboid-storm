package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.metrics.IsoObjectIdPoolMetrics;
import zombie.characters.animals.IsoAnimal;
import zombie.network.GameServer;
import zombie.popman.animal.AnimalInstanceManager;

/**
 * Pure logic behind {@link IsoAnimalRegistryFixPatch}: enforce the invariant {@code
 * AnimalInstanceManager.get(animal.getOnlineID()) == animal} for every animal that is alive in the
 * world on the server.
 *
 * <h2>The bug this heals</h2>
 *
 * <p>Registration into {@code AnimalInstanceManager.AnimalMap} happens in exactly one place for
 * world animals — {@code IsoAnimal.init(AnimalBreed)}, which runs on creation and on load from
 * disk. De-registration happens in {@code IsoAnimal.delete()}. The real ⇄ virtual lifecycle is
 * asymmetric across those two:
 *
 * <ul>
 *   <li>{@code AnimalPopulationManager.virtualizeAnimal} (an animal in {@code AnimalZoneState}
 *       wandering out of the loaded area, {@code IsoMovingObject.postUpdate} line 834) stores the
 *       live {@code IsoAnimal} instance into a {@code VirtualAnimal} and then calls {@code
 *       delete()} — which unregisters it.
 *   <li>{@code AnimalManagerMain.fromWorker} re-realizes that same instance when the chunk comes
 *       back: it fixes up the square, calls {@code addToWorld()} and adds it to {@code
 *       IsoCell.getObjectList()} — but never calls {@code AnimalInstanceManager.add}.
 * </ul>
 *
 * <p>So the animal is back in the world, ticking, colliding and pathing, while {@code AnimalMap}
 * has no entry for it. {@code AnimalSynchronizationManager.sendUpdateToClient} resolves every
 * pending id through {@code AnimalInstanceManager.get(onlineID)} and skips {@code null}, so the
 * animal is never sent to any client again. Players see an empty pasture they can still walk into.
 *
 * <p>A server restart hides it: {@code AnimalCell.load} → {@code VirtualAnimal.load} → {@code
 * IsoAnimal.load} → {@code init()} re-registers everything from disk, which is why animals "come
 * back after a restart and then vanish again" as the world re-virtualizes.
 *
 * <p>Measured on a live 42.20.3 server ~8 h after restart: 180 of 365 animals in the cell object
 * list held an allocated {@code onlineID} that mapped to nothing, and all 180 carried a non-zero
 * {@code removedFromWorldMs} — i.e. every one of them had been through {@code removeFromWorld()},
 * the fingerprint of the {@code delete()}-then-re-realize path.
 *
 * <h2>Outcomes</h2>
 *
 * <ul>
 *   <li>{@link Action#NONE} — invariant already holds.
 *   <li>{@link Action#MISSING_PUT} — id is set but the slot is empty; re-register under the same
 *       id, preserving continuity for any client that still knows it.
 *   <li>{@link Action#COLLISION} — another animal holds the slot (or the animal still carries the
 *       {@code IsoPlayer.onlineId} default of {@code 1} and never had an id of its own); allocate a
 *       fresh id via the probe-for-free {@code AnimalInstanceManager.allocateID()}.
 * </ul>
 *
 * <p>Both healing paths are exported as Prometheus counters (see {@link IsoObjectIdPoolMetrics}).
 *
 * <p>The decision logic ({@link #decideAction}) is split out from the I/O ({@link #ensureMapEntry})
 * so it can be unit-tested without game classes on the classpath.
 */
public final class IsoAnimalMapInvariant {

    /** {@code IsoPlayer.onlineId} field default — an animal that was never registered. */
    public static final short ID_UNASSIGNED = 1;

    /** {@code AnimalInstanceManager.allocateID()} has no free slot left. */
    public static final short ID_INVALID = -1;

    /** Outcome of a single invariant check; surfaced for unit testing and metrics. */
    public enum Action {
        /** Invariant already holds. */
        NONE,
        /** Id is set but the map slot is empty — re-register under the same id. */
        MISSING_PUT,
        /** Slot taken by someone else, or no id was ever assigned — allocate a fresh one. */
        COLLISION
    }

    private IsoAnimalMapInvariant() {}

    /**
     * Pure decision: given an id, a "self" animal reference, and whatever the map currently holds
     * under that id, what (if any) corrective action is required?
     *
     * @param onlineId the animal's current {@code onlineID}
     * @param self the animal whose invariant is being checked
     * @param inMap the value currently mapped to {@code onlineId}, or {@code null} if the slot is
     *     empty
     * @return the action the driver should perform
     */
    public static Action decideAction(short onlineId, Object self, Object inMap) {
        if (inMap == self) {
            return Action.NONE;
        }
        if (onlineId == ID_UNASSIGNED || onlineId == ID_INVALID) {
            return Action.COLLISION;
        }
        if (inMap == null) {
            return Action.MISSING_PUT;
        }
        return Action.COLLISION;
    }

    /**
     * Driver called from the {@code IsoAnimal.update()} exit advice. Looks up the current map
     * entry, picks an action via {@link #decideAction}, and applies it.
     *
     * <p>{@code animalRef} is typed {@code Object} so the inlined advice does not embed a checkcast
     * against {@code IsoAnimal} into the patched method's bytecode. The cast happens here, only on
     * the first actual call, after {@code IsoAnimal} has already been loaded (we are inside its
     * {@code update()} method).
     *
     * @param animalRef the {@code IsoAnimal} whose invariant is being enforced
     */
    public static void ensureMapEntry(Object animalRef) {
        if (!GameServer.server) {
            return;
        }
        IsoAnimal self = (IsoAnimal) animalRef;
        AnimalInstanceManager manager = AnimalInstanceManager.getInstance();
        if (manager == null) {
            return;
        }
        short id = self.getOnlineID();
        Action action = decideAction(id, self, manager.get(id));
        switch (action) {
            case MISSING_PUT:
                manager.add(self, id);
                IsoObjectIdPoolMetrics.recordAnimalOrphanFix();
                break;
            case COLLISION:
                short fresh = manager.allocateID();
                if (fresh == ID_INVALID) {
                    return;
                }
                manager.add(self, fresh);
                IsoObjectIdPoolMetrics.recordAnimalMapCollision();
                break;
            case NONE:
                break;
        }
    }
}
