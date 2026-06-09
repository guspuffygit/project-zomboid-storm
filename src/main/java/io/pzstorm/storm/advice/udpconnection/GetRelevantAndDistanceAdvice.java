package io.pzstorm.storm.advice.udpconnection;

import io.pzstorm.storm.patch.networking.UdpConnectionRelevance;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code UdpConnection.getRelevantAndDistance(float, float, float)}.
 *
 * <p>Short-circuits to {@link Float#POSITIVE_INFINITY} — the vanilla "not relevant" sentinel
 * returned at the end of the original method when no relevance match is found — whenever the
 * connection is not fully connected.
 */
public class GetRelevantAndDistanceAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object self) {
        return !UdpConnectionRelevance.isConnectionReady(self);
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter boolean skipped, @Advice.Return(readOnly = false) float result) {
        if (skipped) {
            result = Float.POSITIVE_INFINITY;
        }
    }
}
