package io.pzstorm.storm.advice.sendworldmapplayerposition;

import io.pzstorm.storm.metrics.MainLoopStepTimings;
import io.pzstorm.storm.metrics.SendWorldMapPlayerPositionMetrics;
import io.pzstorm.storm.worldmap.StormWorldMapVisibilityMemo;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Brackets the all-connections {@code GameServer.sendWorldMapPlayerPosition()} (the no-arg
 * overload): opens the {@link StormWorldMapVisibilityMemo} batch on entry, closes it on exit, and
 * times the whole batch.
 */
public class SendWorldMapPlayerPositionAdvice {

    @Advice.OnMethodEnter
    public static long onEnter() {
        if (!GameServer.server) {
            return 0L;
        }
        long start = System.nanoTime();
        StormWorldMapVisibilityMemo.begin();
        return start <= 0L ? 1L : start;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        StormWorldMapVisibilityMemo.end();
        long elapsed = System.nanoTime() - startNanos;
        SendWorldMapPlayerPositionMetrics.recordNanos(elapsed);
        MainLoopStepTimings.record("GameServer.sendWorldMapPlayerPosition", elapsed);
    }
}
