package io.pzstorm.storm.advice.bitheader;

import io.pzstorm.storm.metrics.BitHeaderMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/** Counts each {@code BitHeader$BitHeaderByte.release()} call. Server-only. */
public class BitHeaderByteReleaseAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        if (!GameServer.server) {
            return;
        }
        BitHeaderMetrics.observeReleaseByte();
    }
}
