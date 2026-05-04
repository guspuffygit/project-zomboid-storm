package io.pzstorm.storm.advice.bitheader;

import io.pzstorm.storm.metrics.BitHeaderMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Server-side: counts each {@code BitHeader$BitHeaderShort.release()} call <em>and</em>
 * short-circuits the original body so {@code pool_short.offer(this)} never runs. See {@code
 * BitHeaderByteReleaseAdvice} for the rationale.
 */
public class BitHeaderShortReleaseAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        if (!GameServer.server) {
            return false;
        }
        BitHeaderMetrics.observeReleaseShort();
        return true;
    }
}
