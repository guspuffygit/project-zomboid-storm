package io.pzstorm.storm.http;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.connection.StormLoginQueueMailbox;
import io.pzstorm.storm.connection.StormPacketDivert;
import io.pzstorm.storm.connection.StormTcpSessionRegistry.Session;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.packet.LoginQueueDonePacketEvent;
import io.pzstorm.storm.util.StormServerTaskQueue;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jetbrains.annotations.Nullable;
import zombie.characters.Capability;
import zombie.core.raknet.UdpConnection;
import zombie.network.LoginQueue;
import zombie.network.PacketTypes;
import zombie.network.packets.connection.LoginQueueDonePacket;

/**
 * Login queue over the game-port TCP channel. Stands in for the three UDP packets of the queue
 * phase: the client's {@code LoginQueueRequest}, the server's {@code ConnectionImmediate} / {@code
 * PlaceInQueue} replies, and {@code LoginQueueDone} with its echo.
 *
 * <p>The vanilla {@link LoginQueue} still owns all state (it runs on the main thread through {@link
 * StormServerTaskQueue}), so Storm's connection-stage metrics and the stalled-connection reaper see
 * a TCP joiner exactly like a UDP one. Server-to-client messages are captured by {@link
 * StormPacketDivert} into the connection's {@link StormLoginQueueMailbox}, which the client drains
 * with short polls; a UDP {@code LoginQueueRequest} from the same connection closes the box and
 * replays anything pending, so a client that falls back mid-queue loses nothing.
 *
 * <p>Message bodies are the vanilla {@code QueuePacket} client-format bytes without the 3-byte
 * packet header; the client parses them with the vanilla packet class.
 */
public class GamePortLoginQueueEndpoints {

    public record DoneRequest(long loadingMillis) {}

    private static final long TASK_TIMEOUT_SECONDS = 15;

    /** Polls block a fixed 4-thread handler pool; keep each one short. */
    static final long MAX_WAIT_MILLIS = 1000;

    @GameHttpEndpoint(path = "/storm/game/login-queue", method = "POST")
    public static void request(HttpRequestEvent event) throws IOException {
        Session session = GamePortHandshakeEndpoints.requireSession(event);
        if (session == null) {
            return;
        }
        UdpConnection connection =
                GamePortHandshakeEndpoints.requireConnection(
                        event, session, Capability.LoginOnServer);
        if (connection == null) {
            return;
        }
        long guid = connection.getConnectedGUID();
        StormLoginQueueMailbox.enable(guid);
        try {
            byte[] first =
                    StormServerTaskQueue.submit(
                                    () -> {
                                        LoginQueue.receiveServerLoginQueueRequest(connection);
                                        return StormLoginQueueMailbox.poll(guid, 0);
                                    })
                            .get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            respond(event, first);
        } catch (TimeoutException e) {
            StormLoginQueueMailbox.disable(guid);
            event.send(503, "server busy");
        } catch (Exception e) {
            StormLoginQueueMailbox.disable(guid);
            LOGGER.error("Login queue request over TCP failed", e);
            event.send(500, "login queue request failed");
        }
    }

    @GameHttpEndpoint(path = "/storm/game/login-queue")
    public static void poll(HttpRequestEvent event) throws IOException {
        Session session = GamePortHandshakeEndpoints.requireSession(event);
        if (session == null) {
            return;
        }
        long guid = session.guid();
        UdpConnection connection =
                GamePortHandshakeEndpoints.requireConnection(
                        event, session, Capability.LoginOnServer);
        if (connection == null) {
            StormLoginQueueMailbox.disable(guid);
            return;
        }
        if (!StormLoginQueueMailbox.isEnabled(guid)) {
            event.send(410, "not in TCP login queue");
            return;
        }
        try {
            respond(event, StormLoginQueueMailbox.poll(guid, waitMillis(event)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            event.send(503, "server shutting down");
        }
    }

    @GameHttpEndpoint(path = "/storm/game/login-queue/done", method = "POST")
    public static void done(HttpRequestEvent event, DoneRequest body) throws IOException {
        Session session = GamePortHandshakeEndpoints.requireSession(event);
        if (session == null) {
            return;
        }
        UdpConnection connection =
                GamePortHandshakeEndpoints.requireConnection(
                        event, session, Capability.LoginOnServer);
        if (connection == null) {
            return;
        }
        long loadingMillis = Math.max(0, body.loadingMillis());
        try {
            StormServerTaskQueue.submit(
                            () -> {
                                receiveDone(connection, loadingMillis);
                                return null;
                            })
                    .get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            event.sendEmpty(200);
        } catch (TimeoutException e) {
            event.send(503, "server busy");
        } catch (Exception e) {
            LOGGER.error("Login queue done over TCP failed", e);
            event.send(500, "login queue done failed");
        }
    }

    /**
     * Main thread. The vanilla handler echoes a {@code LoginQueueDone} packet; the HTTP 200 is that
     * echo, so the captured copy is dropped. The typed event still fires so {@code
     * LoginQueueEarlyRelease} bookkeeping runs as it does for the UDP packet.
     */
    static void receiveDone(UdpConnection connection, long loadingMillis) {
        try (StormPacketDivert.Scope ignored =
                StormPacketDivert.open(connection, PacketTypes.PacketType.LoginQueueDone)) {
            LoginQueue.receiveLoginQueueDone(loadingMillis, connection);
        }
        StormLoginQueueMailbox.disable(connection.getConnectedGUID());
        LoginQueueDonePacket packet = new LoginQueueDonePacket();
        packet.setData(loadingMillis);
        StormEventDispatcher.dispatchEvent(new LoginQueueDonePacketEvent(packet, connection));
    }

    private static void respond(HttpRequestEvent event, @Nullable byte[] message)
            throws IOException {
        if (message == null) {
            event.sendEmpty(204);
            return;
        }
        event.setContentType("application/octet-stream");
        event.send(200, message);
    }

    private static long waitMillis(HttpRequestEvent event) {
        String raw = event.getQueryParams().get("wait");
        if (raw == null) {
            return 0;
        }
        try {
            return Math.max(0, Math.min(MAX_WAIT_MILLIS, Long.parseLong(raw)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
