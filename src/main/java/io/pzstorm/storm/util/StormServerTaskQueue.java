package io.pzstorm.storm.util;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * Bridge for running work on the server main thread from other threads (game-port HTTP pool,
 * background workers). Tasks are drained once per tick by {@code ServerTickAdvice}, which runs at
 * the tail of {@code GameServer.main}'s loop — the same thread that processes packets, so a drained
 * task may safely touch game state that vanilla packet handlers touch.
 *
 * <p>Callers block on the returned future with a timeout; if the server main thread is stalled the
 * timeout fires rather than wedging the calling thread forever. Task exceptions surface through
 * {@link java.util.concurrent.ExecutionException}, never to the tick loop.
 */
public final class StormServerTaskQueue {

    private static final ConcurrentLinkedQueue<FutureTask<?>> QUEUE = new ConcurrentLinkedQueue<>();

    private StormServerTaskQueue() {}

    /** Enqueue a task for the next server tick. Safe to call from any thread. */
    public static <T> Future<T> submit(Callable<T> task) {
        FutureTask<T> future = new FutureTask<>(task);
        QUEUE.add(future);
        return future;
    }

    /** Run all queued tasks. Must only be called from the server main thread. */
    public static void drain() {
        for (FutureTask<?> task; (task = QUEUE.poll()) != null; ) {
            task.run();
        }
    }

    /** Drop queued tasks without running them (tests). Pending futures never complete. */
    public static void reset() {
        QUEUE.clear();
    }
}
