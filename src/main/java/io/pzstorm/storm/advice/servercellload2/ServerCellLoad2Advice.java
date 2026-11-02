package io.pzstorm.storm.advice.servercellload2;

import io.pzstorm.storm.metrics.ChunkHydrationMetrics;
import io.pzstorm.storm.metrics.MainLoopStepTimings;
import io.pzstorm.storm.metrics.ServerCellLoad2Metrics;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoChunk;
import zombie.network.GameServer;

/**
 * Times {@code Load2} and closes the hydration clock opened by the {@code ServerChunkLoader}
 * advices.
 *
 * <p>{@code ServerMap.preupdate} calls {@code Load2} only for cells whose recalc finished, and
 * drops the cell when it throws. Hydration latency is recorded only for a normal return; the
 * wall-clock timer covers both.
 */
public class ServerCellLoad2Advice {

    @Advice.OnMethodEnter
    public static long onEnter() {
        if (!GameServer.server) {
            return 0L;
        }
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Enter long startNanos,
            @Advice.Thrown Throwable thrown,
            @Advice.FieldValue("storm$hydrateStartNanos") long hydrateStart,
            @Advice.FieldValue("storm$recalcStartNanos") long recalcStart,
            @Advice.FieldValue("chunks") IsoChunk[][] chunks) {
        if (!GameServer.server) {
            return;
        }
        if (startNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - startNanos;
        ServerCellLoad2Metrics.recordNanos(elapsed);
        MainLoopStepTimings.record("ServerCell.Load2", elapsed);
        if (thrown == null) {
            ChunkHydrationMetrics.recordCellLoaded(hydrateStart, recalcStart, chunks);
        }
    }
}
