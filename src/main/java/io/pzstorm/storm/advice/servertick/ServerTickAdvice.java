package io.pzstorm.storm.advice.servertick;

import io.pzstorm.storm.connection.SteamPlayerListReconciler;
import io.pzstorm.storm.metrics.ServerTickMetrics;
import io.pzstorm.storm.metrics.StormConnectionMetrics;
import io.pzstorm.storm.metrics.StormConnectionStageMetrics;
import io.pzstorm.storm.zombie.StormZombieTotalCap;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class ServerTickAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) long tickMillis) {
        if (!GameServer.server) {
            return;
        }
        ServerTickMetrics.recordTick(tickMillis);
        StormConnectionMetrics.recordAll();
        StormConnectionStageMetrics.recordAll();
        SteamPlayerListReconciler.sweep();
        StormZombieTotalCap.onServerTick();
    }
}
