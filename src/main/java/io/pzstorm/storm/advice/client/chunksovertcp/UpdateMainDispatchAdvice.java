package io.pzstorm.storm.advice.client.chunksovertcp;

import io.pzstorm.storm.client.StormChunksOverTcp;
import net.bytebuddy.asm.Advice;

/**
 * Woven into {@code WorldStreamer.updateMain}. Dispatches the requests staged by {@link
 * RequestZipListWriteAdvice} only on method exit — after {@code updateMain} published them to
 * {@code sentRequests}. A TCP delivery applied before that publish would match no pending request
 * and be silently discarded, leaving the request pending for good, so the ordering here is
 * load-bearing. The same exit also re-issues over UDP any request the TCP path gave up on.
 */
public class UpdateMainDispatchAdvice {

    @Advice.OnMethodExit
    public static void onExit() {
        StormChunksOverTcp.dispatchStaged();
    }
}
