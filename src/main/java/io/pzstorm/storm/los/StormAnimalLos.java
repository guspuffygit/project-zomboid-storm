package io.pzstorm.storm.los;

import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.AnimalLosFastPathMetrics;
import io.pzstorm.storm.metrics.AnimalUpdateLOSMetrics;
import io.pzstorm.storm.spatial.StormChunkIndex;
import io.pzstorm.storm.spatial.StormObjectList;
import io.pzstorm.storm.spatial.StormSpatialIndex;
import java.util.Stack;
import zombie.GameTime;
import zombie.MovingObjectUpdateScheduler;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.behavior.BaseAnimalBehavior;
import zombie.core.math.PZMath;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoUtils;

/**
 * Server-only replacement for the body of {@code IsoAnimal.updateLOS()}, driven from {@code
 * IsoAnimalUpdateLOSAdvice} after the {@code AnimalLOSTickInterval} stride check.
 *
 * <p>Vanilla walks every {@code IsoMovingObject} in the loaded cell per animal per call and invokes
 * {@code BaseAnimalBehavior.spotted(other, false, dist)} for every zombie and every (non-animal,
 * visible, non-ghost) player on a level within ±1 — thousands of calls per animal on a busy server,
 * of which only the handful within {@link #effectRadius} tiles can do anything: every branch of
 * {@code spotted()} that mutates state is gated on {@code dist} ({@code < 10} tame-player
 * acceptance, {@code < 3} wild flee, {@code <= 10} zombie stress, {@code <= 6} zombie flee, {@code
 * <= spottingDist} tracking XP, {@code < spottingDist} alert/flee). Beyond that radius a call's
 * only effects are {@code spottedChr = null}, the {@code lastAlerted} cooldown decrement, and — for
 * wild animals looking at a moving player — one {@code Rand.Next} draw whose outcome is discarded.
 *
 * <p>This path queries the shared per-tick {@link StormSpatialIndex} for zombies and players in the
 * chunk rectangle covering {@code effectRadius + 1} tiles (plus a chunk of movement slack), runs
 * the vanilla per-object gates and the real {@code spotted()} call on every candidate — with no
 * distance cut of its own, so anything the index returns is handled exactly as vanilla would — and
 * then <em>emulates</em> the two state effects the skipped far calls would have had:
 *
 * <ul>
 *   <li>{@code lastAlerted} is decremented once per skipped call. The skipped-call count is {@link
 *       StormSpatialIndex#spottableNear} (every zombie/player passing the level-independent gates
 *       on a level within ±1 of the animal, counted at tick start) minus the calls actually made
 *       here — exact up to characters mid-stairs at the band's edge and objects whose gate state
 *       changed since tick start.
 *   <li>{@code spottedChr} is reset to {@code null}, which is what the last of vanilla's thousands
 *       of calls leaves behind in all but the vanishingly rare case where the fleeing trigger was
 *       the final object in {@code HashSet} order ({@code fleeFromChr()} consumes {@code
 *       spottedChr} synchronously, so the flee itself is unaffected; {@code AnimalWalkState} reads
 *       the post-scan value; {@code IsoAnimal.alertOtherAnimals}, the only other reader, has no
 *       callers).
 * </ul>
 *
 * The discarded far-object RNG draws are not replayed: they feed nothing but the shared random
 * stream, which multiplayer never reproduces anyway. Candidate order is bucket order rather than
 * {@code HashSet} order; where two candidates both trigger effects in one call the last-writer
 * outcome can differ, exactly as it already does between vanilla runs.
 *
 * <p>Falls back to the vanilla body (returns {@code false}) when the index has no snapshot for the
 * current frame, when the animal has no behavior or definition (vanilla would NPE identically on
 * those), and permanently after the first {@link Throwable}. Main-thread only.
 */
public final class StormAnimalLos {

    /** Tile slack on the effect radius for square-vs-float drift in vanilla's gates. */
    private static final int RADIUS_SLACK = 1;

    /** Chunks of slack for movement between the tick-start snapshot and this call. */
    private static final int SNAPSHOT_SLACK_CHUNKS = 1;

    private static final int CANDIDATE_MASK =
            StormChunkIndex.MASK_ZOMBIE | StormChunkIndex.MASK_PLAYER;

    private static final StormObjectList CANDIDATES = new StormObjectList(256);

    private static boolean failed;

    private StormAnimalLos() {}

    /**
     * Largest distance at which {@code BaseAnimalBehavior.spotted()} can change state for an animal
     * with the given spotting distance: the {@code < 10} / {@code <= 10} tame-acceptance and zombie
     * stress gates, or the definition's {@code spottingDist}, whichever is larger.
     */
    public static int effectRadius(int spottingDist) {
        return Math.max(spottingDist, 10);
    }

    /**
     * Level-independent part of the per-object gates in {@code IsoAnimal.updateLOS}: a zombie that
     * is not reanimated-for-grapple and has a square, or a non-animal player that has a square and
     * is neither invisible nor in ghost mode. Vanilla additionally requires {@code |dz| <= 1} and
     * skips the animal itself.
     */
    public static boolean isSpottable(IsoMovingObject o) {
        if (o instanceof IsoZombie zombie) {
            return !zombie.isReanimatedForGrappleOnly() && zombie.getCurrentSquare() != null;
        }
        if (o instanceof IsoPlayer player) {
            return !(o instanceof IsoAnimal)
                    && player.getCurrentSquare() != null
                    && !player.isInvisible()
                    && !player.isGhostMode();
        }
        return false;
    }

    /**
     * Runs the radius-query body for {@code animalObj}.
     *
     * @return {@code true} if it ran (advice skips the vanilla body), {@code false} to fall through
     *     to vanilla
     */
    public static boolean runOptimized(Object animalObj) {
        if (failed) {
            AnimalLosFastPathMetrics.recordVanilla();
            return false;
        }
        long start = System.nanoTime();
        try {
            IsoAnimal animal = (IsoAnimal) animalObj;
            long frame = MovingObjectUpdateScheduler.instance.getFrameCounter();
            BaseAnimalBehavior behavior = animal.getBehavior();
            AnimalDefinitions adef = animal.adef;
            if (behavior == null || adef == null || !StormSpatialIndex.isReadyFor(frame)) {
                AnimalLosFastPathMetrics.recordVanilla();
                return false;
            }
            run(animal, behavior, adef);
            AnimalUpdateLOSMetrics.recordNanos(System.nanoTime() - start);
            return true;
        } catch (Throwable t) {
            failed = true;
            CANDIDATES.clear();
            StormLogger.LOGGER.error(
                    "StormAnimalLos failed — reverting to vanilla IsoAnimal.updateLOS", t);
            AnimalLosFastPathMetrics.recordVanilla();
            return false;
        }
    }

    private static void run(IsoAnimal animal, BaseAnimalBehavior behavior, AnimalDefinitions adef) {
        float locX = animal.getX();
        float locY = animal.getY();
        float locZ = animal.getZ();
        Stack<IsoMovingObject> spottedList = animal.getSpottedList();
        spottedList.clear();
        spottedList.add(animal);

        int radius = effectRadius(adef.spottingDist) + RADIUS_SLACK;
        int cx0 = StormChunkIndex.chunkOf(locX - radius) - SNAPSHOT_SLACK_CHUNKS;
        int cy0 = StormChunkIndex.chunkOf(locY - radius) - SNAPSHOT_SLACK_CHUNKS;
        int cx1 = StormChunkIndex.chunkOf(locX + radius) + SNAPSHOT_SLACK_CHUNKS;
        int cy1 = StormChunkIndex.chunkOf(locY + radius) + SNAPSHOT_SLACK_CHUNKS;
        CANDIDATES.clear();
        int candidates =
                StormSpatialIndex.collectChunkRect(cx0, cy0, cx1, cy1, CANDIDATE_MASK, CANDIDATES);

        int spottedCalls = 0;
        for (int i = 0; i < candidates; i++) {
            IsoMovingObject movingObject = (IsoMovingObject) CANDIDATES.get(i);
            if (movingObject == animal) {
                continue;
            }
            if (PZMath.abs(movingObject.getZ() - locZ) > 1.0F || !isSpottable(movingObject)) {
                continue;
            }
            float distanceToMovingObject =
                    IsoUtils.DistanceTo(movingObject.getX(), movingObject.getY(), locX, locY);
            behavior.spotted(movingObject, false, distanceToMovingObject);
            spottedCalls++;
        }
        CANDIDATES.clear();

        int emulated = StormSpatialIndex.spottableNear(locZ) - spottedCalls;
        if (emulated > 0) {
            emulateFarCalls(animal, behavior, emulated);
        } else {
            emulated = 0;
        }
        AnimalLosFastPathMetrics.recordOptimized(candidates, spottedCalls, emulated);
    }

    /**
     * Applies the state effects {@code calls} out-of-radius {@code spotted()} invocations would
     * have had: drain {@code lastAlerted} by {@code calls × multiplier} (clamped at 0) and leave
     * {@code spottedChr} null.
     */
    static void emulateFarCalls(IsoAnimal animal, BaseAnimalBehavior behavior, int calls) {
        if (behavior.lastAlerted > 0.0F) {
            behavior.lastAlerted -= calls * GameTime.getInstance().getMultiplier();
            if (behavior.lastAlerted < 0.0F) {
                behavior.lastAlerted = 0.0F;
            }
        }
        animal.spottedChr = null;
    }
}
