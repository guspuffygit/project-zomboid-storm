package io.pzstorm.storm.advice.chunkstream;

import io.pzstorm.storm.metrics.ChunkStreamMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PlayerDownloadServer;

/**
 * Measures the demand side of chunk streaming: how many chunks each {@code RequestZipList} packet
 * asks for.
 *
 * <p>{@code parse} reads an unbounded count and appends the chunks to {@code ccrWaiting} in
 * 20-chunk {@code ClientChunkRequest} buckets. Nothing in the method exposes that count to an exit
 * advice, so entry records the queue depth and exit sums whatever appeared past it.
 *
 * <p>Runs on the main thread — {@code RequestZipListPacket} is {@code handlingType = 1} (server),
 * so it is drained by {@code mainLoopDealWithNetData}.
 */
public class RequestZipListParseAdvice {

    @Advice.OnMethodEnter
    public static int onEnter(@Advice.Argument(1) IConnection connection) {
        if (!GameServer.server || connection == null) {
            return -1;
        }
        PlayerDownloadServer pds = connection.getPlayerDownloadServer();
        if (pds == null) {
            return -1;
        }
        return pds.getWaitingRequests();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Argument(1) IConnection connection, @Advice.Enter int waitingBefore) {
        if (waitingBefore < 0 || connection == null) {
            return;
        }
        ChunkStreamMetrics.recordRequestPacket(connection.getPlayerDownloadServer(), waitingBefore);
    }
}
