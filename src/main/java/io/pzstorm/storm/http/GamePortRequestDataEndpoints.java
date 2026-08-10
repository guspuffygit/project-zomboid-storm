package io.pzstorm.storm.http;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.connection.StormRequestDataSerializer;
import io.pzstorm.storm.connection.StormTcpSessionRegistry.Session;
import io.pzstorm.storm.util.StormServerTaskQueue;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import zombie.network.packets.RequestDataPacket;

/**
 * Serves the loading-phase bulk transfers ({@code RequestDataPacket.RequestID}) over the game-port
 * TCP channel to authenticated Storm connections. Replaces the vanilla UDP path of 1&nbsp;KB {@code
 * PartData} packets with 200&nbsp;KB ACK windows — one HTTP response instead of thousands of
 * round-trip-gated datagrams.
 *
 * <p>Serialization runs on the server main thread via {@link StormServerTaskQueue} (the payload
 * sources are main-thread state); this handler blocks its pool thread until the next tick drains
 * the task, with a timeout so a stalled server returns 503 instead of wedging the pool.
 */
public class GamePortRequestDataEndpoints {

    /** Generous: covers a saturated tick plus multi-MB serialization. */
    private static final long SERIALIZE_TIMEOUT_SECONDS = 15;

    @GameHttpEndpoint(path = "/storm/game/request-data")
    public static void requestData(HttpRequestEvent event) throws IOException {
        Session session = GamePortHandshakeEndpoints.requireSession(event);
        if (session == null) {
            return;
        }
        String idName = event.getQueryParams().get("id");
        RequestDataPacket.RequestID id = parseId(idName);
        if (id == null || id == RequestDataPacket.RequestID.ConnectionDetails) {
            // ConnectionDetails needs the per-connection LogonResult and is pushed by the
            // server during login; it is not servable on demand.
            event.send(400, "unknown request id: " + idName);
            return;
        }
        try {
            byte[] payload =
                    StormServerTaskQueue.submit(() -> StormRequestDataSerializer.serialize(id))
                            .get(SERIALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            event.setContentType("application/octet-stream");
            event.send(200, payload);
        } catch (TimeoutException e) {
            event.send(503, "server busy");
        } catch (Exception e) {
            LOGGER.error("Failed to serialize request data {} for TCP transfer", id, e);
            event.send(500, "serialization failed");
        }
    }

    @org.jetbrains.annotations.Nullable
    private static RequestDataPacket.RequestID parseId(
            @org.jetbrains.annotations.Nullable String idName) {
        if (idName == null) {
            return null;
        }
        try {
            return RequestDataPacket.RequestID.valueOf(idName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
