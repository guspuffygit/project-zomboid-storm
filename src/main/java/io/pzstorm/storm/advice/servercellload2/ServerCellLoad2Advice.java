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
 * <p>{@code Load2} has two returns: it drains the recalc queue first and only does real work —
 * {@code RecalcAll2} plus {@code loadVehicles} — on the {@code true} path. The {@code false} path
 * dominates by call count, so hydration latency is recorded only when the return says the cell
 * actually finished. The wall-clock timer below deliberately covers both, because the drain itself
 * runs on the main thread and its cost belongs in the tick budget either way.
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
            @Advice.Return boolean loaded,
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
        if (loaded) {
            ChunkHydrationMetrics.recordCellLoaded(hydrateStart, recalcStart, chunks);
        }
    }
}
