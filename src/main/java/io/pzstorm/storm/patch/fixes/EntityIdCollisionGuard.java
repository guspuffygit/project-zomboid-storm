package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.EntityIdCollisionMetrics;
import zombie.entity.GameEntity;
import zombie.entity.util.LongMap;
import zombie.iso.IsoObject;

/**
 * Keeps an entity id collision from breaking {@code GameEntityManager.UnregisterEntity}, behind
 * {@link EntityIdCollisionPatch}.
 *
 * <h2>The bug this heals</h2>
 *
 * <p>An {@code IsoObject}'s entity id is positional, and {@code getEntityNetID} forces every floor
 * to index 0 wherever it sits in the square's list. Building a floor under an object already on the
 * square appends the floor, then {@code BuildRecipeCode.floor.OnCreate} removes the ground tile:
 * {@code [grass, crate, floor]} becomes {@code [crate, floor]}. The crate's real index and the
 * floor's forced index now give one id. {@code checkEntityIDChange} logs {@code expected null}, and
 * the crate is left holding an id whose map entry is the floor's.
 *
 * <p>{@code UnregisterEntity} removes the entry first and compares after, so it takes the floor's
 * entry, throws {@code Stored entity mismatch}, and leaves both objects in the engine. The floor,
 * with no entry left, can never leave: at its own unregister the remove returns null and vanilla
 * skips the whole body. The same silent leak hits the crate when the floor goes first.
 *
 * <p>The throw fires inside whatever called it. A chunk unload stops part way through the chunk,
 * and a wall upgrade fails after it has spent its materials.
 *
 * <h2>The fix</h2>
 *
 * <p>Put the leaving object under the id first, so vanilla runs its normal path for it, then put
 * the map back as it was. No vanilla code is copied. With another object in the entry the meta
 * offload is switched off, because the {@code MetaEntity} would need that same id: the components
 * stay on the object and save with it.
 *
 * <p>Registered server side only, and only {@code IsoObject}s are touched, which is every throw
 * seen so far. A {@code MetaEntity} keeps vanilla's behaviour.
 */
public final class EntityIdCollisionGuard {

    /** The entry {@link #before} replaced. A null occupant means there was no entry at all. */
    private record Displaced(long id, GameEntity occupant) {}

    private EntityIdCollisionGuard() {}

    /**
     * Runs on entry to {@code UnregisterEntity}. Reads no id for an object that was never
     * registered: most tile objects are not, and asking would recompute theirs.
     *
     * @return what to hand back to {@link #after}, or null when the entry was already this object
     */
    @SuppressWarnings("unchecked")
    public static Object before(Object entityRef, Object mapRef) {
        if (!(entityRef instanceof IsoObject object) || !object.isAddedToEngine()) {
            return null;
        }
        long id = object.getEntityNetID();
        if (id == -1L) {
            return null;
        }
        LongMap<GameEntity> map = (LongMap<GameEntity>) mapRef;
        GameEntity held;
        synchronized (map) {
            held = map.put(id, object);
        }
        return held == object ? null : new Displaced(id, held);
    }

    /** True when another object holds the id, so the meta offload has to stay off. */
    public static boolean isOccupied(Object displacedRef) {
        return displacedRef instanceof Displaced displaced && displaced.occupant != null;
    }

    /**
     * Runs on exit from {@code UnregisterEntity}, thrown or not. The occupant gets its entry back,
     * and with no occupant nothing of ours is left behind: vanilla returns early, keeping the entry
     * written above, when {@code addedToEntityManager} is already false.
     */
    @SuppressWarnings("unchecked")
    public static void after(Object entityRef, Object mapRef, Object displacedRef) {
        LongMap<GameEntity> map = (LongMap<GameEntity>) mapRef;
        Displaced displaced = (Displaced) displacedRef;
        synchronized (map) {
            if (displaced.occupant != null) {
                map.put(displaced.id, displaced.occupant);
            } else if (map.get(displaced.id) == entityRef) {
                map.remove(displaced.id);
            }
        }
        if (displaced.occupant == null) {
            EntityIdCollisionMetrics.recordMissingEntry();
            return;
        }
        EntityIdCollisionMetrics.recordCollision();
        IsoObject object = (IsoObject) entityRef;
        LOGGER.warn(
                "Entity id {} at {},{},{} is held by {}, unregistered {} around it",
                displaced.id,
                object.getSquare().getX(),
                object.getSquare().getY(),
                object.getSquare().getZ(),
                displaced.occupant.getEntityFullTypeDebug(),
                object.getEntityFullTypeDebug());
    }
}
