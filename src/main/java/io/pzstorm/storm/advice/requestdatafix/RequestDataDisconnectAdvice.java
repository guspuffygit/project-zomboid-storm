package io.pzstorm.storm.advice.requestdatafix;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.RequestDataMetrics;
import java.util.Iterator;
import java.util.List;
import net.bytebuddy.asm.Advice;
import zombie.core.raknet.UdpConnection;

/**
 * Replaces {@code RequestDataManager.disconnect(UdpConnection)}, whose vanilla body reaps other
 * players' in-flight world downloads:
 *
 * <pre>{@code
 * this.requests.removeIf(rd -> currentTime - rd.creationTime > 60000L
 *       || rd.connectionGuid == connection.getConnectedGUID());
 * }</pre>
 *
 * <p>The age clause is global — it is evaluated against <b>every</b> entry whenever <b>any</b>
 * player disconnects. {@code creationTime} is only refreshed when an ACK triggers the next {@code
 * sendData} burst, so a joiner on a slow or lossy link whose transfer goes more than 60 seconds
 * between bursts loses its server-side transfer state to a stranger's disconnect. The client keeps
 * waiting for data that will never come ({@code GameLoadingRequestData()} has no timeout), and its
 * eventual ACK used to crash in {@code ACKWasReceived} (see {@link RequestDataAckAdvice}). On a
 * busy server disconnects happen every few minutes, so this is the main real-world trigger of the
 * "joiner wedged on the loading screen" failure.
 *
 * <p>The replacement scopes removal to the disconnecting connection's own entries and keeps a
 * belt-and-suspenders stale sweep at {@link #STALE_AGE_MS 10 minutes} — matching Storm's
 * stalled-connection reap horizon, by which point the owning connection is gone and its own
 * disconnect should already have cleaned up. Stale reaps are counted as {@code
 * storm_requestdata_stale_purged_total} and logged, because each one is a leak that vanilla's
 * per-connection path failed to cover.
 */
public class RequestDataDisconnectAdvice {

    /** Entries older than this are leaked, not live: no ACK for 10 minutes. */
    public static final long STALE_AGE_MS = 600_000L;

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.This Object self, @Advice.Argument(0) UdpConnection connection) {
        return RequestDataDisconnectAdvice.run(self, connection.getConnectedGUID());
    }

    /** Returns {@code false} only when reflection is unavailable and vanilla should run. */
    public static boolean run(Object manager, long connectionGuid) {
        if (!RequestDataReflection.init()) {
            return false;
        }
        try {
            purge(
                    RequestDataReflection.requests(manager),
                    connectionGuid,
                    System.currentTimeMillis());
        } catch (Throwable t) {
            LOGGER.error(
                    "RequestDataFix: disconnect cleanup failed for connection {}",
                    connectionGuid,
                    t);
        }
        return true;
    }

    /**
     * Removes the disconnecting connection's entries, plus any entry older than {@link
     * #STALE_AGE_MS} regardless of owner. Returns the number of stale entries reaped.
     */
    public static int purge(List<?> requests, long connectionGuid, long now)
            throws IllegalAccessException {
        if (!RequestDataReflection.init()) {
            throw new IllegalStateException("RequestDataManager reflection unavailable");
        }
        int stale = 0;
        for (Iterator<?> it = requests.iterator(); it.hasNext(); ) {
            Object entry = it.next();
            if (RequestDataReflection.entryGuid(entry) == connectionGuid) {
                it.remove();
            } else if (now - RequestDataReflection.entryCreationTime(entry) > STALE_AGE_MS) {
                it.remove();
                stale++;
                RequestDataMetrics.stalePurged();
                LOGGER.warn(
                        "RequestDataFix: reaped a stale {} transfer for connection {} — its own"
                                + " disconnect never cleaned it up",
                        RequestDataReflection.entryId(entry),
                        RequestDataReflection.entryGuid(entry));
            }
        }
        return stale;
    }
}
