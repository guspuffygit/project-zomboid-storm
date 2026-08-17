package io.pzstorm.storm.advice.playerdownloadserverremoveolder;

import io.pzstorm.storm.metrics.ChunkStreamMetrics;
import net.bytebuddy.asm.Advice;

/**
 * Counts chunks dropped because the client had already asked for the same {@code wx,wy}.
 *
 * <p>A {@code true} return is the server observing a client re-request while the first request was
 * still sitting in {@code ccrWaiting}, unanswered. Since 42.20.3 removed the client's flat 8-second
 * resend timer, the re-ask is driven by the server's own ChunkNotReady replies or by the chunk map
 * re-wanting a cancelled chunk. That makes this the closest thing to client-side stall telemetry
 * that can be read without shipping anything to the client — which matters, because players'
 * clients are not scrapeable.
 *
 * <p>Exit-only and gated on the return value, so the common {@code false} path adds one branch to a
 * method that is already doing a linear scan.
 */
public class CancelDuplicateChunkAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.Return boolean cancelled) {
        if (cancelled) {
            ChunkStreamMetrics.recordDuplicateCancelled();
        }
    }
}
