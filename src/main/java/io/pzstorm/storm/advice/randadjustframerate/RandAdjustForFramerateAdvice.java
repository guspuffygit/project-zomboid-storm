package io.pzstorm.storm.advice.randadjustframerate;

import io.pzstorm.storm.patch.performance.StormRandFramerate;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Replaces the server branch of {@code RandInterface.AdjustForFramerate(int)} with tick-rate-aware
 * scaling ({@code chance * lockFps / 30}) instead of vanilla's hardcoded {@code chance *
 * 0.33333334F} (10 TPS baked in). Bit-identical to vanilla at the default 10 TPS; see {@link
 * StormRandFramerate}.
 *
 * <p>The client branch (vanilla scales by {@code PerformanceSettings.getLockFPS() / 30}) is left to
 * the original body: the skip only fires when {@code GameServer.server} is true. No {@code
 * onThrowable} on exit — when skipped the body cannot throw, and when not skipped no overwrite is
 * wanted.
 */
public class RandAdjustForFramerateAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return GameServer.server;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter boolean skipped,
            @Advice.Argument(0) int chance,
            @Advice.Return(readOnly = false) int result) {
        if (skipped) {
            result = StormRandFramerate.adjustForServerFramerate(chance);
        }
    }
}
