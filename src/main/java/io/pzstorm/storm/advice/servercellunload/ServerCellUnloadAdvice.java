package io.pzstorm.storm.advice.servercellunload;

import io.pzstorm.storm.metrics.MainLoopStepTimings;
import io.pzstorm.storm.metrics.ServerCellUnloadMetrics;
import io.pzstorm.storm.patch.performance.StormCellWarmer;
import io.pzstorm.storm.patch.performance.StormCellWarmingConfig;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;
import zombie.network.ServerMap;

public class ServerCellUnloadAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.This Object cell, @Advice.Local("startNanos") long startNanos) {
        if (!GameServer.server) {
            startNanos = 0L;
            return 0;
        }
        if (StormCellWarmingConfig.isEnabled()
                && cell instanceof ServerMap.ServerCell sc
                && StormCellWarmer.warm(sc)) {
            startNanos = 0L;
            return 1;
        }
        startNanos = System.nanoTime();
        return 0;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Local("startNanos") long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - startNanos;
        ServerCellUnloadMetrics.recordNanos(elapsed);
        MainLoopStepTimings.record("ServerCell.Unload", elapsed);
    }
}
