package io.pzstorm.storm.advice.bitheader;

import io.pzstorm.storm.metrics.BitHeaderMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;
import zombie.util.io.BitHeader;

/**
 * Counts each call to {@code BitHeader.getHeader(HeaderSize, ByteBuffer, boolean)}, broken down by
 * {@code HeaderSize} ordinal. This is the entry point for both {@code allocWrite} and {@code
 * allocRead}, so it captures every caller.
 *
 * <p>Server-only — the lag and allocation pressure we are characterizing are server-side.
 */
public class BitHeaderGetHeaderAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) BitHeader.HeaderSize size) {
        if (!GameServer.server) {
            return;
        }
        if (size == null) {
            return;
        }
        BitHeaderMetrics.observeGetHeader(size.ordinal());
    }
}
