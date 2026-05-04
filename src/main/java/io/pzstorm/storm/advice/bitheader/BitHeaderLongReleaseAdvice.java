package io.pzstorm.storm.advice.bitheader;

import io.pzstorm.storm.metrics.BitHeaderMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Server-side: counts each {@code BitHeader$BitHeaderLong.release()} call <em>and</em>
 * short-circuits the original body so {@code pool_long.offer(this)} never runs. See {@code
 * BitHeaderByteReleaseAdvice} for the rationale.
 */
public class BitHeaderLongReleaseAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        if (!GameServer.server) {
            return false;
        }
        BitHeaderMetrics.observeReleaseLong();
        return true;
    }
}
