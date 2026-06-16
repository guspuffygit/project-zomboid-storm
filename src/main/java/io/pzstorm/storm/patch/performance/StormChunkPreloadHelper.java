package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.MainLoopStepTimings;
import java.util.concurrent.LinkedBlockingQueue;
import zombie.iso.IsoChunk;
import zombie.network.ServerMap;

/**
 * Member-substitution target for {@code ServerChunkLoader.RecalcAllThread.runInner}'s call to
 * {@code this.fromThread.add(cell)}. Replaces that single publish call with an optional preload
 * step (gated by {@link StormChunkPreloadConfig#isEnabled()}) followed by the original add.
 *
 * <p>The preload step runs {@code doLoadGridsquare()} on every non-null chunk in the cell, marks
 * each chunk in {@link StormChunkPreloadState}, and only then republishes the cell to the main
 * thread. By the time main reaches {@code RecalcAll2()}, every chunk's body has been skipped by the
 * entry advice on {@code IsoChunk.doLoadGridsquare} and the heavy per-chunk work has already
 * happened off-thread.
 */
public final class StormChunkPreloadHelper {

    /**
     * Set on the worker thread inside {@link #preloadAndAdd} for the duration of the preload pass
     * so the {@code IsoChunk.doLoadGridsquare} advice can attribute the resulting work to a
     * separate timing bucket ({@code IsoChunk.doLoadGridsquare.preload}) instead of the main thread
     * bucket.
     */
    public static final ThreadLocal<Boolean> IN_PRELOAD = ThreadLocal.withInitial(() -> false);

    private StormChunkPreloadHelper() {}

    /**
     * Substitution for {@code this.fromThread.add(cell)} inside {@code
     * ServerChunkLoader.RecalcAllThread.runInner}. Returns the same boolean {@code
     * LinkedBlockingQueue.add(...)} would have returned.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean preloadAndAdd(LinkedBlockingQueue queue, Object cellObject) {
        if (!(cellObject instanceof ServerMap.ServerCell)) {
            return queue.add(cellObject);
        }
        ServerMap.ServerCell cell = (ServerMap.ServerCell) cellObject;
        if (StormChunkPreloadConfig.isEnabled()) {
            long startNanos = System.nanoTime();
            IN_PRELOAD.set(Boolean.TRUE);
            try {
                for (int cx = 0; cx < 8; cx++) {
                    for (int cy = 0; cy < 8; cy++) {
                        IsoChunk chunk = cell.chunks[cx][cy];
                        if (chunk == null) {
                            continue;
                        }
                        try {
                            chunk.doLoadGridsquare();
                            StormChunkPreloadState.mark(chunk);
                        } catch (Throwable t) {
                            LOGGER.warn(
                                    "Storm: chunk preload failed for {},{} — falling back to"
                                            + " main-thread doLoadGridsquare: {}",
                                    chunk.wx,
                                    chunk.wy,
                                    t.toString(),
                                    t);
                        }
                    }
                }
            } finally {
                IN_PRELOAD.set(Boolean.FALSE);
            }
            MainLoopStepTimings.record("RecalcAllThread.preload", System.nanoTime() - startNanos);
        }
        return queue.add(cell);
    }
}
