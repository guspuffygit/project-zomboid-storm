package io.pzstorm.storm.advice.bitheader;

import io.pzstorm.storm.metrics.BitHeaderMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/** Counts each {@code BitHeader$BitHeaderLong.release()} call. Server-only. */
public class BitHeaderLongReleaseAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        if (!GameServer.server) {
            return;
        }
        BitHeaderMetrics.observeReleaseLong();
    }
}
