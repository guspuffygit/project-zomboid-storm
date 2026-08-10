package io.pzstorm.storm.advice.client.requestdataovertcp;

import io.pzstorm.storm.client.StormRequestDataOverTcp;
import net.bytebuddy.asm.Advice;

/**
 * Entry hook on {@code GameClient.GameLoadingRequestData()} (loader thread). When the TCP channel
 * delivers all loading-phase payloads, the vanilla UDP request chain is skipped; on any failure the
 * helper returns {@code false} having applied nothing, and the vanilla body runs untouched.
 */
public class GameLoadingRequestDataAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return StormRequestDataOverTcp.tryFetchAll();
    }
}
