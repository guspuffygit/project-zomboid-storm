package io.pzstorm.storm.advice.zombiespotplayer;

import io.pzstorm.storm.metrics.ZombieSpotPlayerMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class TestZombieSpotPlayerAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        if (!GameServer.server) {
            return;
        }
        ZombieSpotPlayerMetrics.recordCall();
    }
}
