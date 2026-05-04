package io.pzstorm.storm.advice.bitheader;

import io.pzstorm.storm.metrics.BitHeaderMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/** Counts each {@code BitHeader$BitHeaderShort.release()} call. Server-only. */
public class BitHeaderShortReleaseAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        if (!GameServer.server) {
            return;
        }
        BitHeaderMetrics.observeReleaseShort();
    }
}
