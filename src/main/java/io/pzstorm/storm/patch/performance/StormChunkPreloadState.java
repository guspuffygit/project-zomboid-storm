package io.pzstorm.storm.patch.performance;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import zombie.iso.IsoChunk;

/**
 * Tracks which {@link IsoChunk} instances have already had {@code doLoadGridsquare()} run by the
 * recalc-worker preload path. The main thread's later {@code RecalcAll2()} loop checks-and-clears
 * the mark via the {@code IsoChunk.doLoadGridsquare} entry advice so the per-chunk body is skipped.
 *
 * <p>Backing storage is a synchronized {@link WeakHashMap} key set so unloaded chunks don't pin the
 * GC root forever — the chunk is dereferenced on unload and silently drops out of the mark set.
 */
public final class StormChunkPreloadState {

    private static final Set<IsoChunk> PRELOADED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private StormChunkPreloadState() {}

    /** Called by the worker after it finishes running {@code doLoadGridsquare()} on a chunk. */
    public static void mark(IsoChunk chunk) {
        if (chunk == null) {
            return;
        }
        PRELOADED.add(chunk);
    }

    /**
     * Atomically returns {@code true} and clears the mark if {@code chunk} was previously marked;
     * otherwise returns {@code false} and leaves the set unchanged. Used by the {@code
     * IsoChunk.doLoadGridsquare} entry advice to short-circuit the second call from the main
     * thread.
     */
    public static boolean consume(IsoChunk chunk) {
        if (chunk == null) {
            return false;
        }
        return PRELOADED.remove(chunk);
    }

    /** Test-only — drops every mark currently tracked. */
    static void clearForTest() {
        PRELOADED.clear();
    }

    /** Test-only — current mark count. */
    static int sizeForTest() {
        return PRELOADED.size();
    }
}
