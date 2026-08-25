package io.pzstorm.storm.advice.requestdatafix;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.RequestDataMetrics;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.jetbrains.annotations.Nullable;
import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RequestDataPacket;

/**
 * Replaces {@code RequestDataManager.ACKWasReceived(RequestID, UdpConnection, int)}, whose vanilla
 * loop indexes one past the end of the {@code requests} list:
 *
 * <pre>{@code
 * for (int i = 0; i <= this.requests.size(); i++) {   // <= — off by one
 *    if (this.requests.get(i).connectionGuid == connection.getConnectedGUID()) { ... }
 * }
 * }</pre>
 *
 * <p>Whenever an ACK arrives for which no entry matches — the list is empty, the transfer just
 * completed on an exact 200 KiB boundary, or the entry was purged by {@code disconnect()}'s global
 * 60-second sweep (see {@link RequestDataDisconnectAdvice}) — the final iteration throws {@code
 * IndexOutOfBoundsException}. {@code GameServer} swallows it per-packet, the ACK is lost, {@code
 * sendData} is never called again, and the joining client spins forever in {@code
 * GameClient.GameLoadingRequestData()}, which has no timeout, until Storm's stalled-connection
 * reaper kills the connection. Vanilla also matches by connection only and silently drops the ACK
 * when the found entry's {@code id} differs.
 *
 * <p>The replacement scans with correct bounds, matches on <b>both</b> connection GUID and request
 * id (load-bearing once more than one transfer per connection can be in flight), resumes the
 * matched transfer, and counts unmatched ACKs as {@code storm_requestdata_orphan_ack_total} instead
 * of throwing.
 */
public class RequestDataAckAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.This Object self,
            @Advice.Argument(0) RequestDataPacket.RequestID id,
            @Advice.Argument(1) UdpConnection connection) {
        return RequestDataAckAdvice.run(self, id, connection.getConnectedGUID());
    }

    /** Returns {@code false} only when reflection is unavailable and vanilla should run. */
    public static boolean run(Object manager, Object id, long connectionGuid) {
        if (!RequestDataReflection.init()) {
            return false;
        }
        try {
            Object entry = findRequest(RequestDataReflection.requests(manager), connectionGuid, id);
            if (entry != null) {
                RequestDataReflection.sendData(manager, entry);
            } else {
                // transfer already completed or was purged — a late or duplicate ACK, drop it
                RequestDataMetrics.orphanAck();
                LOGGER.debug(
                        "RequestDataFix: dropped orphan ACK for {} from connection {}",
                        id,
                        connectionGuid);
            }
        } catch (Throwable t) {
            LOGGER.error(
                    "RequestDataFix: ACKWasReceived({}) failed for connection {}",
                    id,
                    connectionGuid,
                    t);
        }
        return true;
    }

    /**
     * The in-flight entry matching both the connection and the request id, or {@code null}. Never
     * throws on an empty or non-matching list — the two states vanilla crashed on.
     */
    public static @Nullable Object findRequest(List<?> requests, long connectionGuid, Object id)
            throws IllegalAccessException {
        if (!RequestDataReflection.init()) {
            throw new IllegalStateException("RequestDataManager reflection unavailable");
        }
        for (int i = 0; i < requests.size(); i++) {
            Object entry = requests.get(i);
            if (RequestDataReflection.entryGuid(entry) == connectionGuid
                    && RequestDataReflection.entryId(entry) == id) {
                return entry;
            }
        }
        return null;
    }
}
