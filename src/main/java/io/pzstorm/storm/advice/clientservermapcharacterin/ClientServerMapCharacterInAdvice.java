package io.pzstorm.storm.advice.clientservermapcharacterin;

import io.pzstorm.storm.metrics.ClientServerMapCharacterInMetrics;
import io.pzstorm.storm.metrics.MainLoopStepTimings;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class ClientServerMapCharacterInAdvice {

    @Advice.OnMethodEnter
    public static long onEnter() {
        if (!GameServer.server) {
            return 0L;
        }
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter long startNanos) {
        if (!GameServer.server) {
            return;
        }
        if (startNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - startNanos;
        ClientServerMapCharacterInMetrics.recordNanos(elapsed);
        MainLoopStepTimings.record("ClientServerMap.characterIn", elapsed);
    }
}
