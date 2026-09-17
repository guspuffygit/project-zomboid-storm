package io.pzstorm.storm.http;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.connection.StormPacketDivert;
import io.pzstorm.storm.connection.StormTcpSessionRegistry.Session;
import io.pzstorm.storm.util.StormServerTaskQueue;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jetbrains.annotations.Nullable;
import zombie.characters.Capability;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.network.PacketTypes;
import zombie.network.packets.service.ChecksumPacket;

/**
 * Lua/script/anim checksum exchange over the game-port TCP channel. Each request body is one
 * client-format {@code ChecksumPacket} (the bytes after the 3-byte packet header); the response is
 * the server's reply packet in the same form. The vanilla {@link ChecksumPacket#parseServer} runs
 * on the main thread and still writes {@code connection.checksumState}, AntiCheat and userlog
 * entries, so kick timers and Storm's connection-stage metrics behave as on UDP.
 */
public class GamePortChecksumEndpoints {

    private static final long TASK_TIMEOUT_SECONDS = 15;

    /** A file-checksum packet carries 20 MD5s plus paths; 64 KB leaves headroom. */
    static final int MAX_BODY_BYTES = 64 * 1024;

    @GameHttpEndpoint(path = "/storm/game/checksum", method = "POST")
    public static void checksum(HttpRequestEvent event) throws IOException {
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
        byte[] body = event.getRequestBody();
        if (body.length > MAX_BODY_BYTES) {
            event.send(413, "checksum packet too large");
            return;
        }
        if (body.length < Short.BYTES) {
            event.send(400, "checksum packet too short");
            return;
        }
        try {
            byte[] reply =
                    StormServerTaskQueue.submit(() -> exchange(connection, body))
                            .get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (reply == null) {
                event.sendEmpty(204);
                return;
            }
            event.setContentType("application/octet-stream");
            event.send(200, reply);
        } catch (TimeoutException e) {
            event.send(503, "server busy");
        } catch (Exception e) {
            LOGGER.error("Checksum exchange over TCP failed", e);
            event.send(500, "checksum exchange failed");
        }
    }

    /** Main thread. Runs the vanilla handler and captures the reply it would have sent. */
    static @Nullable byte[] exchange(UdpConnection connection, byte[] body) {
        try (StormPacketDivert.Scope scope =
                StormPacketDivert.open(connection, PacketTypes.PacketType.Checksum)) {
            new ChecksumPacket()
                    .parseServer(new ByteBufferReader(ByteBuffer.wrap(body)), connection);
            List<byte[]> replies = scope.take();
            if (replies.size() > 1) {
                LOGGER.warn(
                        "Checksum handler sent {} packets for one request; delivering the first",
                        replies.size());
            }
            return replies.isEmpty() ? null : replies.get(0);
        }
    }
}
