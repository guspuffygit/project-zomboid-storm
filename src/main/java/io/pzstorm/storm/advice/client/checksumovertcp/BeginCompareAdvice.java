package io.pzstorm.storm.advice.client.checksumovertcp;

import io.pzstorm.storm.client.StormChecksumOverTcp;
import net.bytebuddy.asm.Advice;

/**
 * Entry hook on {@code NetChecksum.Comparer.beginCompare()} (loader thread). When the whole
 * exchange completes over TCP the vanilla body is skipped; on failure the helper resets the
 * comparer and returns {@code false} so the vanilla UDP exchange starts clean.
 */
public class BeginCompareAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return StormChecksumOverTcp.tryCompare();
    }
}
