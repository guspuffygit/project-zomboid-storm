package io.pzstorm.storm.advice.playerupdatelos;

import io.pzstorm.storm.metrics.PlayerUpdateLOSMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class IsoPlayerUpdateLOSAdvice {

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
        PlayerUpdateLOSMetrics.recordNanos(System.nanoTime() - startNanos);
    }
}
