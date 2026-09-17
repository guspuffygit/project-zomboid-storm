package io.pzstorm.storm.advice.client.loginqueueovertcp;

import io.pzstorm.storm.client.StormLoginQueueOverTcp;
import net.bytebuddy.asm.Advice;

/**
 * Entry hook on {@code LoadingQueueState.update()} (main thread). Applies queue messages received
 * over TCP before vanilla reads its {@code done} flag, on the thread vanilla applies UDP ones.
 */
public class LoadingQueueStateUpdateAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
        StormLoginQueueOverTcp.drainOnMainThread();
    }
}
