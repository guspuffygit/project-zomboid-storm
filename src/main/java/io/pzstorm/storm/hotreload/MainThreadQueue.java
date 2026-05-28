package io.pzstorm.storm.hotreload;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Hands work from an arbitrary thread (the HTTP dispatcher) to the game's {@code MainThread} and
 * waits for the result. Used by the client side of {@link HotReloadEndpoints}, where touching Lua
 * or GL state off the main thread is unsafe. {@link #drain()} must be pumped from a main-thread
 * tick (see {@link HotReloadEndpoints#drainMainThreadQueue}); the dedicated server has no such tick
 * and runs hot-reload work directly, so this queue is never used there.
 */
public final class MainThreadQueue {

    private static final String MAIN_THREAD_NAME = "MainThread";
    private static final ConcurrentLinkedQueue<Job<?>> QUEUE = new ConcurrentLinkedQueue<>();

    private MainThreadQueue() {}

    public static boolean isMainThread() {
        return MAIN_THREAD_NAME.equals(Thread.currentThread().getName());
    }

    public static <T> T runOnMain(Supplier<T> work) {
        if (isMainThread()) {
            return work.get();
        }
        Job<T> job = new Job<>(work);
        QUEUE.add(job);
        try {
            return job.future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            throw new RuntimeException(
                    "Timed out waiting for main thread (drain not running?)", te);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    private static long lastTickLogMs;

    public static void drain() {
        long now = System.currentTimeMillis();
        if (now - lastTickLogMs > 5000) {
            LOGGER.debug("MainThreadQueue tick on thread={}", Thread.currentThread().getName());
            lastTickLogMs = now;
        }
        Job<?> job;
        while ((job = QUEUE.poll()) != null) {
            job.run();
        }
    }

    private static final class Job<T> {
        final Supplier<T> work;
        final CompletableFuture<T> future = new CompletableFuture<>();

        Job(Supplier<T> work) {
            this.work = work;
        }

        void run() {
            try {
                future.complete(work.get());
            } catch (Throwable t) {
                LOGGER.error("MainThreadQueue job threw", t);
                future.completeExceptionally(t);
            }
        }
    }
}
