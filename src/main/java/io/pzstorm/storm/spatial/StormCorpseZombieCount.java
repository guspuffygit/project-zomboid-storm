package io.pzstorm.storm.spatial;

import io.pzstorm.storm.logging.StormLogger;
import zombie.MovingObjectUpdateScheduler;
import zombie.config.BooleanConfigOption;
import zombie.iso.CorpseCount;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;

/**
 * Serves the {@code ZombieHealthImpact} half of {@code CorpseCount.getCorpseCount(int, int, int,
 * IsoBuilding)} from {@link StormSpatialIndex} instead of vanilla's 25×25-square walk, wired in by
 * {@code CorpseCountZombieIndexPatch}.
 *
 * <p>Vanilla's corpse half is already O(1) (incrementally maintained per chunk level); when the
 * {@code zombieHealthImpact} sandbox option is on it additionally walks a 25×25 tile box centered
 * on the caller's chunk corner — 625 {@code getGridSquare} lookups plus a linear {@code
 * movingObjects} scan per square, per player, per tick from {@code BodyDamage.UpdateIllness} (~5.4
 * ms/tick at 128 players). The replacement queries the zombie partition of the shared spatial-index
 * snapshot for the chunks covering the box (plus one chunk of snapshot-drift slack) and applies the
 * exact live filter per candidate: the zombie's {@code movingSq} — the square whose {@code
 * movingObjects} list vanilla's {@code getZombieCount()} would have counted it in — must be at
 * level {@code z}, inside the box, and in the caller's building.
 *
 * <p>Exactness: the only consumer-visible quantity is {@code min(count, maxCorpseCount)} — {@code
 * getSicknessFromCorpsesRate} returns 0 below 6 and clamps at {@code maxCorpseCount}, and vanilla
 * itself early-returns once the running count reaches it (so vanilla's raw value above the cap is
 * already iteration-order dependent). Within that observable range the divergence is bounded by one
 * tick of zombie movement between the snapshot and the query — the same soundness argument as the
 * LOS consumers of the index.
 *
 * <p>Fail-soft: when the index has no snapshot for the current frame (caller outside the
 * scheduler's update pass) the substituted option read returns the real value, vanilla's walk runs
 * untouched, and {@link #augment} adds nothing. Any throw latches the fast path off permanently and
 * restores vanilla behavior. Main-thread only by contract, like every index consumer.
 */
public final class StormCorpseZombieCount {

    /** Vanilla's box half-width: the {@code dx/dy -12..12} loop in {@code getCorpseCount}. */
    private static final int BOX_RADIUS = 12;

    /** Extra chunks queried around the box to absorb snapshot-to-query position drift. */
    private static final int SLACK_CHUNKS = 1;

    private static final StormObjectList CANDIDATES = new StormObjectList(64);

    private static boolean failed;

    /**
     * Per-call handshake between the substituted option read and the exit advice: set only when
     * {@link #readZombieHealthImpact} steered this call past the vanilla walk, consumed by {@link
     * #augment}. Guarantees the exit advice can never add on top of a vanilla walk — even if the
     * {@code MemberSubstitution} silently fails to match after a game update, {@code augment} sees
     * the flag unset and becomes a no-op (vanilla behavior, fast path dead).
     */
    private static boolean servedThisCall;

    private StormCorpseZombieCount() {}

    /**
     * Substituted for the single {@code zombieHealthImpact.getValue()} read inside {@code
     * getCorpseCount(int, int, int, IsoBuilding)}: returns {@code false} while the fast path is
     * healthy (skipping vanilla's 25×25 walk; {@link #augment} supplies the zombie count instead),
     * and the real option value when the fast path is unavailable — steering the method back onto
     * the untouched vanilla walk.
     */
    public static boolean readZombieHealthImpact(Object option) {
        servedThisCall = false;
        boolean real = ((BooleanConfigOption) option).getValue();
        if (!real || failed) {
            return real;
        }
        try {
            if (StormSpatialIndex.isReadyFor(
                    MovingObjectUpdateScheduler.instance.getFrameCounter())) {
                servedThisCall = true;
                return false;
            }
            return real;
        } catch (Throwable t) {
            fail(t);
            return real;
        }
    }

    /**
     * Adds the index-derived zombie count for the box at {@code (wx*8, wy*8)} level {@code z} to
     * {@code count} when the fast path served this call, mirroring vanilla's building match and its
     * early return at {@code maxCorpseCount}. Returns {@code count} unchanged when vanilla's own
     * walk ran (option off, no snapshot, latched off, or the call never reached the option read —
     * vanilla's corpse half early-returns at {@code maxCorpseCount}).
     */
    public static int augment(int count, int wx, int wy, int z, Object buildingObj) {
        if (!servedThisCall) {
            return count;
        }
        servedThisCall = false;
        try {
            int max = CorpseCount.maxCorpseCount;
            if (count >= max) {
                return count;
            }
            int x = wx * 8;
            int y = wy * 8;
            CANDIDATES.clear();
            StormSpatialIndex.collectChunkRect(
                    StormChunkIndex.chunkOf(x - BOX_RADIUS) - SLACK_CHUNKS,
                    StormChunkIndex.chunkOf(y - BOX_RADIUS) - SLACK_CHUNKS,
                    StormChunkIndex.chunkOf(x + BOX_RADIUS) + SLACK_CHUNKS,
                    StormChunkIndex.chunkOf(y + BOX_RADIUS) + SLACK_CHUNKS,
                    StormChunkIndex.MASK_ZOMBIE,
                    CANDIDATES);
            for (int i = 0; i < CANDIDATES.size() && count < max; i++) {
                IsoMovingObject zombie = (IsoMovingObject) CANDIDATES.get(i);
                IsoGridSquare sq = zombie.getMovingSquare();
                if (sq == null || sq.getZ() != z) {
                    continue;
                }
                int dx = sq.getX() - x;
                if (dx < -BOX_RADIUS || dx > BOX_RADIUS) {
                    continue;
                }
                int dy = sq.getY() - y;
                if (dy < -BOX_RADIUS || dy > BOX_RADIUS) {
                    continue;
                }
                if (sq.getBuilding() != buildingObj) {
                    continue;
                }
                count++;
            }
            CANDIDATES.clear();
            return count;
        } catch (Throwable t) {
            fail(t);
            return count;
        }
    }

    private static void fail(Throwable t) {
        failed = true;
        StormLogger.LOGGER.error(
                "StormCorpseZombieCount failed — reverting to vanilla getCorpseCount zombie scan",
                t);
    }

    /** Test-only: clears the failure latch and the per-call handshake. */
    static void resetForTest() {
        failed = false;
        servedThisCall = false;
    }
}
