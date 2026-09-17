package io.pzstorm.storm.advice.client.chunksovertcp;

import io.pzstorm.storm.client.StormChunksOverTcp;
import net.bytebuddy.asm.Advice;

/**
 * Woven into {@code WorldStreamer.receiveChunkPart} and {@code receiveNotRequired}. Both mutate the
 * non-thread-safe {@code pendingRequests} list and are vanilla-called only from the UdpEngine
 * thread; with Storm's TCP worker also delivering through them, this serializes the two callers on
 * {@link StormChunksOverTcp#RECEIVE_LOCK} (reentrant, so the worker's own lock-hold is fine).
 */
public class ReceiveLockAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        StormChunksOverTcp.RECEIVE_LOCK.lock();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        StormChunksOverTcp.RECEIVE_LOCK.unlock();
    }
}
