package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.PathfindChunkTaskDrainMetrics;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Drains the native pathfinder's chunk task queue by time budget instead of by vanilla's fixed ten
 * updates per frame.
 *
 * <p>{@code PathfindNative.addChunkToWorld} serialises the chunk into a direct {@code ByteBuffer}
 * on a {@code ChunkUpdateTask} and adds the task to {@code PathfindNativeThread.chunkTaskQueue}, an
 * unbounded {@code ConcurrentLinkedQueue}. {@code PathfindNativeThread.updateThread} then executes
 * at most ten {@code ChunkUpdateTask}s per frame, and the same frame goes on to run up to two
 * {@code findPath} calls. The frame limiter caps the thread at 20 frames a second, so the ceiling
 * is 200 chunk updates a second. On a busy server the thread is saturated by {@code findPath} (97%
 * of a core by the JVM's own per-thread CPU time), frames stretch well past 50 ms, and the drain
 * measured there was 25 to 42 a second.
 *
 * <p>Chunk adds arrive much faster than that: every chunk load is one, and cell warming adds 64 per
 * rewarm and 64 per eviction. Nothing coalesces or drops a queued task, so the queue grows for the
 * life of the process and every task in it pins its direct buffer. The tasks are reachable, so no
 * collection frees them. Measured on a 110 player server 5.9 hours into a boot: 10.0 million chunk
 * adds, 9.1 million direct buffers still alive (7.6 GiB), growing by about 300 a second and
 * monotonic through two ZGC major cycles. The queue is first in first out, so the native nav map
 * was also hours behind the world.
 *
 * <p>Executing a chunk task is a JNI copy of at most a few KB: in 22 minutes of samples on that
 * server, one sample landed in {@code updateChunk} against 9,449 in {@code findPath}. The cap is
 * what starves the queue, not the cost of the work. This drain runs at the top of {@code
 * updateThread}, executes queued chunk tasks in order until the queue is empty or the budget is
 * spent, and hands each one to {@code taskReturnQueue} exactly as vanilla does. Whatever is left
 * falls through to the vanilla loop behind it, which still runs unmodified.
 *
 * <p>The native side sees the same calls in the same order as vanilla, only sooner, which is the
 * sequence it already sees on a server quiet enough for the queue to stay empty.
 *
 * <p>{@code -Dstorm.pathfind.chunkTaskBudgetMs=<n>} sets the per-frame budget in milliseconds
 * (default {@value #DEFAULT_BUDGET_MILLIS}); {@code 0} turns the drain off and leaves vanilla's ten
 * per frame. If the task interface cannot be resolved the drain latches off and vanilla runs.
 */
public final class PathfindChunkTaskDrain {

    static final long DEFAULT_BUDGET_MILLIS = 10L;

    private static final long BUDGET_NANOS =
            Math.max(0L, Long.getLong("storm.pathfind.chunkTaskBudgetMs", DEFAULT_BUDGET_MILLIS))
                    * 1_000_000L;

    private static final String TASK_INTERFACE = "zombie.pathfind.nativeCode.IPathfindTask";

    private static volatile boolean latchedOff;
    private static Consumer<Object> executor;

    private PathfindChunkTaskDrain() {}

    /**
     * Called from the {@code updateThread} enter advice, on the pathfinder thread. The queues are
     * typed {@code Queue<Object>} because their element type is package-private to the game.
     */
    public static void drain(Queue<Object> chunkTasks, Queue<Object> returned) {
        if (BUDGET_NANOS == 0L || latchedOff || chunkTasks.isEmpty()) {
            return;
        }
        Consumer<Object> execute = executor;
        if (execute == null) {
            execute = resolveExecutor();
            if (execute == null) {
                return;
            }
            executor = execute;
        }
        int drained = drain(chunkTasks, returned, execute, System::nanoTime, BUDGET_NANOS);
        PathfindChunkTaskDrainMetrics.record(drained, !chunkTasks.isEmpty());
    }

    /**
     * Pure loop: execute tasks in queue order and return each one, until the queue is empty or the
     * budget is spent. The budget is checked after a task, so at least one task runs per call.
     *
     * @return the number of tasks executed
     */
    static int drain(
            Queue<Object> chunkTasks,
            Queue<Object> returned,
            Consumer<Object> execute,
            LongSupplier nanoClock,
            long budgetNanos) {
        long deadline = nanoClock.getAsLong() + budgetNanos;
        int drained = 0;
        Object task;
        while ((task = chunkTasks.poll()) != null) {
            execute.accept(task);
            returned.add(task);
            drained++;
            if (nanoClock.getAsLong() - deadline >= 0L) {
                break;
            }
        }
        return drained;
    }

    /** Resolves {@code IPathfindTask.execute()}; on failure latches the drain off and logs once. */
    static Consumer<Object> resolveExecutor() {
        try {
            Method method = Class.forName(TASK_INTERFACE).getDeclaredMethod("execute");
            method.setAccessible(true);
            return task -> invoke(method, task);
        } catch (ReflectiveOperationException | RuntimeException e) {
            latchedOff = true;
            LOGGER.warn(
                    "Pathfinder chunk task drain is off, vanilla's ten per frame applies: could"
                            + " not resolve {}.execute()",
                    TASK_INTERFACE,
                    e);
            return null;
        }
    }

    private static void invoke(Method method, Object task) {
        try {
            method.invoke(task);
        } catch (InvocationTargetException e) {
            // A task that throws aborts the frame in vanilla and is logged by the thread's run
            // loop. Keep that: rethrow what the task threw, not the reflection wrapper.
            throw PathfindChunkTaskDrain.<RuntimeException>sneaky(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
