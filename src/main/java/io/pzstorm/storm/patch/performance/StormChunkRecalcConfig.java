package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.network.ServerChunkLoader;
import zombie.network.ServerMap;

/**
 * Runtime knob for the {@code Storm.ChunkRecalcThreads} sandbox option.
 *
 * <p>Vanilla {@link ServerChunkLoader} owns a single private {@code RecalcAllThread} that drains
 * {@code addRecalcJob(ServerCell)} into edge-wiring work and republishes the cell to the main
 * thread. When several cells finish loading in the same batch, the main-thread freeze inside {@code
 * ServerMap.preupdate()}/{@code ServerCell.Load2()} is proportional to the batch size because the
 * worker delivers cells serially.
 *
 * <p>The pool always pre-allocates {@link #PRE_ALLOCATED} helper threads regardless of the
 * configured value; {@link #setThreads(int)} just updates how many of them are gated through to
 * {@code toThread.take()} (see {@link StormChunkRecalcGate}). This mirrors the {@code
 * StormServerLosConfig} pattern: rebuild-free runtime tuning, no thread churn on sandbox push.
 *
 * <p>Pool init is one-shot and happens on the first call to {@link #setThreads(int)} (which the
 * sandbox applier triggers at {@code OnServerStarted}). The 15 extra workers share the same {@code
 * toThread}/{@code fromThread} queues as the vanilla worker, accessed via reflection on {@code
 * ServerChunkLoader.threadRecalc}.
 *
 * <p>Concurrency caveat: two active workers can process adjacent cells simultaneously, both
 * touching the boundary squares between cells. Cells are 64×64 squares; boundary contention is rare
 * in practice (most concurrently-loaded cells belong to different player chunk frusta) but not
 * impossible. The cap of {@link #MAX_CONFIGURED} trades parallelism against boundary race risk.
 */
public final class StormChunkRecalcConfig {

    public static final int DEFAULT_THREADS = 1;
    public static final int MIN_CONFIGURED = 1;
    public static final int MAX_CONFIGURED = 16;
    public static final int PRE_ALLOCATED = 16;

    private static final AtomicInteger CONFIGURED = new AtomicInteger(DEFAULT_THREADS);
    private static final AtomicBoolean POOL_INITIALIZED = new AtomicBoolean(false);

    private StormChunkRecalcConfig() {}

    public static int getConfigured() {
        return CONFIGURED.get();
    }

    /**
     * Pushes the requested active worker count through the gate. Clamps to {@link #MIN_CONFIGURED}
     * to {@link #MAX_CONFIGURED}. On the first invocation, pre-allocates all {@link #PRE_ALLOCATED}
     * helper threads (vanilla worker = slot 0 plus {@code PRE_ALLOCATED - 1} extras); subsequent
     * invocations are O(1) — just the {@code CONFIGURED} update and an unpark sweep.
     */
    public static int setThreads(int requested) {
        int clamped = Math.max(MIN_CONFIGURED, Math.min(MAX_CONFIGURED, requested));
        CONFIGURED.set(clamped);
        StormPerformanceSandboxMetrics.setChunkRecalcThreads(clamped);
        ensurePoolInitialized();
        StormChunkRecalcGate.unparkAll();
        return clamped;
    }

    private static void ensurePoolInitialized() {
        if (!POOL_INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        try {
            spawnAllExtras();
        } catch (ReflectiveOperationException e) {
            POOL_INITIALIZED.set(false);
            LOGGER.error(
                    "Storm: failed to pre-allocate {} extra RecalcAllThread workers: {}",
                    PRE_ALLOCATED - 1,
                    e.toString(),
                    e);
        }
    }

    private static void spawnAllExtras() throws ReflectiveOperationException {
        Field chunkLoaderField = ServerMap.ServerCell.class.getDeclaredField("chunkLoader");
        chunkLoaderField.setAccessible(true);
        Object chunkLoader = chunkLoaderField.get(null);
        if (chunkLoader == null) {
            throw new IllegalStateException("ServerMap.ServerCell.chunkLoader is null");
        }
        Field threadRecalcField = ServerChunkLoader.class.getDeclaredField("threadRecalc");
        threadRecalcField.setAccessible(true);
        Object recalcThread = threadRecalcField.get(chunkLoader);
        if (recalcThread == null) {
            throw new IllegalStateException("ServerChunkLoader.threadRecalc is null");
        }

        Class<?> recalcCls = recalcThread.getClass();
        Field toField = recalcCls.getDeclaredField("toThread");
        toField.setAccessible(true);
        Field fromField = recalcCls.getDeclaredField("fromThread");
        fromField.setAccessible(true);
        Object sharedTo = toField.get(recalcThread);
        Object sharedFrom = fromField.get(recalcThread);

        Constructor<?> ctor = recalcCls.getDeclaredConstructor(ServerChunkLoader.class);
        ctor.setAccessible(true);

        int extras = PRE_ALLOCATED - 1;
        for (int i = 1; i <= extras; i++) {
            Object newWorker = ctor.newInstance(chunkLoader);
            toField.set(newWorker, sharedTo);
            fromField.set(newWorker, sharedFrom);
            Thread thread = (Thread) newWorker;
            thread.setName("RecalcAll-storm-" + i);
            thread.setDaemon(true);
            thread.setPriority(Thread.MAX_PRIORITY);
            StormChunkRecalcGate.registerSlot(thread, i);
            thread.start();
        }
        LOGGER.info(
                "Storm: pre-allocated {} extra RecalcAllThread workers (total pool = {}); active"
                        + " slots gated by Storm.ChunkRecalcThreads",
                extras,
                PRE_ALLOCATED);
    }

    /** Test-only — exposes the shared input queue for reflection wiring assertions. */
    static LinkedBlockingQueue<?> peekRecalcInputQueueForTest()
            throws ReflectiveOperationException {
        Field chunkLoaderField = ServerMap.ServerCell.class.getDeclaredField("chunkLoader");
        chunkLoaderField.setAccessible(true);
        Object chunkLoader = chunkLoaderField.get(null);
        Field threadRecalcField = ServerChunkLoader.class.getDeclaredField("threadRecalc");
        threadRecalcField.setAccessible(true);
        Object recalcThread = threadRecalcField.get(chunkLoader);
        Field toField = recalcThread.getClass().getDeclaredField("toThread");
        toField.setAccessible(true);
        return (LinkedBlockingQueue<?>) toField.get(recalcThread);
    }
}
