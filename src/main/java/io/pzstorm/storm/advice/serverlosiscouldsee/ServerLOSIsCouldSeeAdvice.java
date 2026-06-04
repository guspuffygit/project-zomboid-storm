package io.pzstorm.storm.advice.serverlosiscouldsee;

import io.pzstorm.storm.metrics.ServerLOSIsCouldSeeMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class ServerLOSIsCouldSeeAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Return boolean result) {
        if (!GameServer.server) {
            return;
        }
        ServerLOSIsCouldSeeMetrics.recordCall(result);
    }
}
