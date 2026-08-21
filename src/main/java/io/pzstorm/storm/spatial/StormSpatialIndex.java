package io.pzstorm.storm.spatial;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.los.StormAnimalLos;
import io.pzstorm.storm.metrics.MainLoopStepTimings;
import io.pzstorm.storm.metrics.SpatialIndexMetrics;
import java.util.Collection;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoMovingObject;
import zombie.vehicles.BaseVehicle;

/**
 * Server-wide, once-per-tick spatial index of every {@code IsoMovingObject} in the loaded cell —
 * the single shared scan that replaces the per-consumer whole-cell walks ({@code
 * IsoPlayer.updateLOS}, {@code IsoAnimal.updateLOS}, …). Rebuilt by {@code
 * MovingObjectSchedulerStartFrameAdvice} at the end of {@code
 * MovingObjectUpdateScheduler.startFrame()}, i.e. before any object updates in the tick; every
 * consumer that runs inside the scheduler's update pass sees a snapshot taken at tick start.
 *
 * <p><b>Why a snapshot is sound.</b> {@code IsoCell.objectList} only changes through the cell's
 * deferred add/remove processing (a direct mutation would {@code ConcurrentModificationException}
 * vanilla's own {@code startFrame} walk), so the set of objects a consumer would have iterated is
 * exactly the set captured here. Only positions drift within the tick, by at most one tick of
 * movement; consumers widen their query rectangle by a chunk and re-apply their exact live checks
 * on the candidates, which is outcome-equivalent to vanilla's walk (vanilla's {@code HashSet}
 * iteration already mixes pre- and post-update positions within a tick).
 *
 * <p><b>Fail-soft.</b> A rebuild that throws leaves the index unpublished for that tick, so every
 * consumer falls back to its full-scan path via {@link #isReadyFor(long)}; {@link
 * #MAX_CONSECUTIVE_FAILURES} consecutive failures latch the index off permanently (logged once).
 * Main-thread only by contract.
 */
public final class StormSpatialIndex {

    static final int MAX_CONSECUTIVE_FAILURES = 50;

    private static final StormChunkIndex INDEX = new StormChunkIndex();

    /**
     * Zombies and players that pass the level-independent gates of {@code IsoAnimal.updateLOS}
     * ({@link StormAnimalLos#isSpottable}), bucketed by integer z level. Lets the animal-LOS fast
     * path count how many {@code spotted()} calls vanilla's whole-cell walk would have made for an
     * animal on a given level without walking the cell.
     */
    private static final LevelHistogram SPOTTABLE_BY_LEVEL = new LevelHistogram();

    private static int consecutiveFailures;
    private static boolean latchedOff;

    private StormSpatialIndex() {}

    /**
     * Rebuilds the snapshot from {@code objects} for tick {@code frame}.
     *
     * @param objects the live {@code IsoCell.objectList}
     * @param frame the scheduler frame counter this snapshot belongs to
     */
    public static void rebuild(Collection<IsoMovingObject> objects, long frame) {
        if (latchedOff) {
            return;
        }
        long start = System.nanoTime();
        try {
            INDEX.beginTick(frame);
            SPOTTABLE_BY_LEVEL.clear();
            for (IsoMovingObject o : objects) {
                int type = typeOf(o);
                INDEX.add(o, o.getX(), o.getY(), type);
                if ((type == StormChunkIndex.TYPE_ZOMBIE || type == StormChunkIndex.TYPE_PLAYER)
                        && StormAnimalLos.isSpottable(o)) {
                    SPOTTABLE_BY_LEVEL.add(LevelHistogram.levelOf(o.getZ()));
                }
            }
            INDEX.endTick();
            consecutiveFailures = 0;
            long nanos = System.nanoTime() - start;
            SpatialIndexMetrics.recordRebuild(nanos, INDEX.size(), INDEX.bucketCount());
            MainLoopStepTimings.record("StormSpatialIndex.rebuild", nanos);
        } catch (Throwable t) {
            INDEX.invalidate();
            SpatialIndexMetrics.recordFailure();
            consecutiveFailures++;
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                latchedOff = true;
                LOGGER.error(
                        "StormSpatialIndex: {} consecutive rebuild failures — disabling the shared"
                                + " spatial index; LOS consumers stay on their full-scan paths",
                        consecutiveFailures,
                        t);
            } else if (consecutiveFailures == 1) {
                LOGGER.warn("StormSpatialIndex: rebuild failed, falling back this tick", t);
            }
        }
    }

    /** True when a snapshot for exactly {@code frame} is published. */
    public static boolean isReadyFor(long frame) {
        return INDEX.isReady() && INDEX.frame() == frame;
    }

    /** Type partition for an object; animals are checked before players because they extend it. */
    public static int typeOf(IsoMovingObject o) {
        if (o instanceof IsoZombie) {
            return StormChunkIndex.TYPE_ZOMBIE;
        }
        if (o instanceof IsoAnimal) {
            return StormChunkIndex.TYPE_ANIMAL;
        }
        if (o instanceof IsoPlayer) {
            return StormChunkIndex.TYPE_PLAYER;
        }
        if (o instanceof BaseVehicle) {
            return StormChunkIndex.TYPE_VEHICLE;
        }
        return StormChunkIndex.TYPE_OTHER;
    }

    /** See {@link StormChunkIndex#collectTileRect}. */
    public static int collectTileRect(
            float minX, float minY, float maxX, float maxY, int typeMask, StormObjectList out) {
        return INDEX.collectTileRect(minX, minY, maxX, maxY, typeMask, out);
    }

    /** See {@link StormChunkIndex#collectChunkRect}. */
    public static int collectChunkRect(
            int cx0, int cy0, int cx1, int cy1, int typeMask, StormObjectList out) {
        return INDEX.collectChunkRect(cx0, cy0, cx1, cy1, typeMask, out);
    }

    /** Objects of {@code type} in the current snapshot. */
    public static int totalOf(int type) {
        return INDEX.totalOf(type);
    }

    /**
     * Spottable zombies and players (see {@link StormAnimalLos#isSpottable}) whose integer level is
     * within ±1 of {@code z}'s level in the current snapshot — the number of {@code spotted()}
     * calls vanilla's {@code IsoAnimal.updateLOS} would make for an animal at {@code z}. Vanilla
     * gates on {@code |dz| <= 1.0} in float, so this is exact except for characters mid-stairs at
     * the band's edge.
     */
    public static int spottableNear(float z) {
        return SPOTTABLE_BY_LEVEL.sumNear(LevelHistogram.levelOf(z), 1);
    }

    /** Objects in the current snapshot. */
    public static int size() {
        return INDEX.size();
    }

    /** Test-only: clears the failure latch. */
    static void resetForTest() {
        consecutiveFailures = 0;
        latchedOff = false;
        INDEX.invalidate();
        SPOTTABLE_BY_LEVEL.clear();
    }
}
