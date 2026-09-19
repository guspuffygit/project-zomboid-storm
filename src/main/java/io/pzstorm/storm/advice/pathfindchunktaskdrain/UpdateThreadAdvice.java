package io.pzstorm.storm.advice.pathfindchunktaskdrain;

import io.pzstorm.storm.patch.performance.PathfindChunkTaskDrain;
import java.util.Queue;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the top of {@code zombie.pathfind.nativeCode.PathfindNativeThread.updateThread()}.
 *
 * <p>Hands the thread's two task queues to {@link PathfindChunkTaskDrain}, which executes queued
 * chunk tasks by time budget before vanilla's ten-per-frame loop gets to them. Vanilla's loop is
 * left in place and picks up whatever the budget did not reach. See the driver for the measurement.
 *
 * <p>The fields are read as raw {@code Queue}s: their element type, {@code IPathfindTask}, is
 * package-private to the game and cannot be named from here.
 */
public class UpdateThreadAdvice {

    @Advice.OnMethodEnter
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void onEnter(
            @Advice.FieldValue("chunkTaskQueue") Queue chunkTaskQueue,
            @Advice.FieldValue("taskReturnQueue") Queue taskReturnQueue) {
        PathfindChunkTaskDrain.drain(chunkTaskQueue, taskReturnQueue);
    }
}
