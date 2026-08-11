package io.pzstorm.storm.advice.chunkstream;

import io.pzstorm.storm.metrics.ChunkStreamMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Counts the replies that answer a chunk request with nothing.
 *
 * <p>The {@code sameOnServer} flag is the whole story: true means the client's CRC matched and the
 * payload was legitimately skipped, false means the request is being abandoned — a serialize
 * exception, a pruned duplicate, a send error, or the retry ladder running out.
 *
 * <p>Called from both the main thread ({@code update}, {@code cancelDuplicateChunk}) and the
 * download worker ({@code sendArray}, {@code sendChunk}'s catch block).
 */
public class SendNotRequiredAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(1) boolean sameOnServer) {
        if (!GameServer.server) {
            return;
        }
        ChunkStreamMetrics.recordNotRequired(sameOnServer);
    }
}
