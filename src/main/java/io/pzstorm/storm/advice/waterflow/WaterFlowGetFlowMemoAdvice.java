package io.pzstorm.storm.advice.waterflow;

import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.asm.Advice;
import org.joml.Vector2f;
import zombie.iso.IsoGridSquare;

/**
 * Advice for the static {@code zombie.iso.IsoWaterFlow.getFlow(IsoGridSquare, int, int, Vector2f)}.
 *
 * <p>The vanilla body makes three O(N) passes over every water-flow point on the map and then scans
 * a 10x10 grid-square neighborhood (100 {@code getGridSquare} + property lookups) — per call. It is
 * called four times per water square from {@code IsoWaterGeometry.init()} whenever a chunk's render
 * geometry is (re)built, which clusters in chunk-streaming bursts: live-client hitch profiling near
 * the Louisville waterfront attributed 10.6% of hitch-frame time to it.
 *
 * <p>The result is keyed purely on world position — flow points load once per map and water flags
 * do not change at runtime — and each corner position is shared by four adjacent squares, so a
 * position-keyed memo pays off even within a single streaming burst. The one wrinkle: at the
 * streaming frontier the neighborhood scan can see not-yet-loaded (null) squares and dampen the
 * speed, and vanilla self-corrects on the next rebuild. A TTL bounds that staleness instead of
 * freezing the first answer; flow itself never changes in-session, so the TTL only exists to let
 * frontier-dampened speeds heal.
 *
 * <p>Values are {@code long[]{deadlineMillis, flowBits, speedBits}} to keep the advice body free of
 * any value class the target loader might not see. The size guard is a crude clear-all — the map
 * only grows while new water squares stream in, so it fires rarely if ever.
 */
public class WaterFlowGetFlowMemoAdvice {

    public static final long TTL_MILLIS =
            Long.getLong("storm.experimental.clientperf.waterFlowTtlMs", 30000L);

    public static final int MAX_ENTRIES = 262144;

    public static final ConcurrentHashMap<Long, long[]> CACHE = new ConcurrentHashMap<>();

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static long[] onEnter(
            @Advice.Argument(0) IsoGridSquare square,
            @Advice.Argument(1) int ax,
            @Advice.Argument(2) int ay) {
        if (square == null) {
            return null;
        }
        long key = ((long) (square.x + ax) << 32) | ((square.y + ay) & 0xFFFFFFFFL);
        long[] entry = CACHE.get(key);
        if (entry == null || System.currentTimeMillis() > entry[0]) {
            return null;
        }
        return entry;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Argument(0) IsoGridSquare square,
            @Advice.Argument(1) int ax,
            @Advice.Argument(2) int ay,
            @Advice.Argument(3) Vector2f out,
            @Advice.Enter long[] cached,
            @Advice.Return(readOnly = false) Vector2f ret) {
        if (cached != null) {
            out.set(Float.intBitsToFloat((int) cached[1]), Float.intBitsToFloat((int) cached[2]));
            ret = out;
            return;
        }
        if (square == null || ret == null) {
            return;
        }
        if (CACHE.size() >= MAX_ENTRIES) {
            CACHE.clear();
        }
        long key = ((long) (square.x + ax) << 32) | ((square.y + ay) & 0xFFFFFFFFL);
        CACHE.put(
                key,
                new long[] {
                    System.currentTimeMillis() + TTL_MILLIS,
                    Float.floatToRawIntBits(ret.x),
                    Float.floatToRawIntBits(ret.y)
                });
    }
}
