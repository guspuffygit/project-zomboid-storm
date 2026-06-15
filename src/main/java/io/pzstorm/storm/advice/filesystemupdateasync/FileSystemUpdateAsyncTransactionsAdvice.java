package io.pzstorm.storm.advice.filesystemupdateasync;

import io.pzstorm.storm.metrics.FileSystemUpdateAsyncTransactionsMetrics;
import io.pzstorm.storm.metrics.MainLoopStepTimings;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class FileSystemUpdateAsyncTransactionsAdvice {

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
        FileSystemUpdateAsyncTransactionsMetrics.recordNanos(elapsed);
        MainLoopStepTimings.record("FileSystem.updateAsyncTransactions", elapsed);
    }
}
