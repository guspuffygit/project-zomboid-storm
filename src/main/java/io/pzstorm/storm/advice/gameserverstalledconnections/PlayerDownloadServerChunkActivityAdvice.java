package io.pzstorm.storm.advice.gameserverstalledconnections;

import net.bytebuddy.asm.Advice;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;

/**
 * Enter advice for {@code PlayerDownloadServer.getClientChunkRequest} — the single funnel every
 * incoming chunk-request wave passes through ({@code RequestZipListPacket.parse} allocates through
 * it, as does the download worker's retry path; the {@code RequestLargeArea} worker command is dead
 * code in this build, so nothing bypasses this method). Stamps the owning connection in {@link
 * StalledConnectionReaper} so an actively downloading client keeps sliding its reap clock instead
 * of being disconnected mid-load, which hard-crashes vanilla clients — see the reaper's class doc.
 *
 * <p>Runs on whichever thread processes the request packet and on the download worker thread; the
 * reaper's stamp store is a concurrent map, so no synchronization is needed here.
 */
public class PlayerDownloadServerChunkActivityAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.FieldValue("connection") UdpConnection connection) {
        if (!GameServer.server) {
            return;
        }
        if (connection != null) {
            StalledConnectionReaper.recordChunkActivity(connection);
        }
    }
}
