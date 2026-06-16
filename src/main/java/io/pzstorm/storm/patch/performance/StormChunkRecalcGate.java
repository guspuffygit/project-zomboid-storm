package io.pzstorm.storm.patch.performance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * Per-worker activation gate for the pre-allocated {@code RecalcAllThread} pool. Each spawned extra
 * worker is registered here with its slot index (1-based); the vanilla worker is implicit slot 0
 * (always active). The pool always carries {@link StormChunkRecalcConfig#PRE_ALLOCATED} workers
 * regardless of the {@code Storm.ChunkRecalcThreads} sandbox option; this gate is what makes "only
 * N of them are drained" possible without rebuilding the pool.
 *
 * <p>The gate is invoked via {@link RecalcAllRunInnerPatch}, which substitutes the {@code
 * toThread.take()} call inside {@code RecalcAllThread.runInner} with {@link #takeOrPark}. Slots
 * whose index is {@code >= configured} park before pulling from the queue; the cell stays in the
 * queue for an active slot to consume. When {@link StormChunkRecalcConfig#setThreads(int)} raises
 * the configured count, {@link #unparkAll()} wakes every registered worker so the newly-active
 * slots can proceed past their gate.
 */
public final class StormChunkRecalcGate {

    private static final Map<Thread, Integer> SLOT_OF_THREAD = new ConcurrentHashMap<>();

    private StormChunkRecalcGate() {}

    /** Registers an extra worker's 1-based slot index. Called by the spawn routine after start. */
    public static void registerSlot(Thread worker, int slotIndex) {
        SLOT_OF_THREAD.put(worker, slotIndex);
    }

    /** Wakes every registered extra worker so they re-evaluate their gate. */
    public static void unparkAll() {
        for (Thread t : SLOT_OF_THREAD.keySet()) {
            LockSupport.unpark(t);
        }
    }

    /**
     * Substitution target for {@code this.toThread.take()} inside {@code RecalcAllThread.runInner}.
     * Parks the current thread while its slot index is {@code >=
     * StormChunkRecalcConfig.getConfigured()}; otherwise delegates to {@code queue.take()}.
     *
     * <p>The vanilla worker (not in {@link #SLOT_OF_THREAD}) is treated as slot {@code 0} and is
     * always active when configured {@code >= 1} (which the clamp enforces).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Object takeOrPark(LinkedBlockingQueue queue) throws InterruptedException {
        Integer slot = SLOT_OF_THREAD.get(Thread.currentThread());
        if (slot == null) {
            return queue.take();
        }
        while (slot >= StormChunkRecalcConfig.getConfigured()) {
            LockSupport.park(StormChunkRecalcGate.class);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
        return queue.take();
    }
}
