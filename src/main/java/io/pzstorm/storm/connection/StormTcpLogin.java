package io.pzstorm.storm.connection;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.EveryOneMinuteEvent;
import io.pzstorm.storm.util.StormServerTaskQueue;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jetbrains.annotations.Nullable;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.RequestDataManager;
import zombie.network.packets.RequestDataPacket;
import zombie.network.packets.connection.LoginPacket;

/**
 * Server half of the LoginPacket over the game-port TCP channel. The client posts the vanilla
 * packet bytes; {@link #process} runs the vanilla parser and {@code processServer} on the main
 * thread inside a {@link StormPacketDivert} scope that captures every packet vanilla answers with
 * ({@code RequestData(ConnectionDetails)}, {@code SpawnRegion}, {@code MetaGrid}, {@code
 * AccessDenied}, {@code GoogleAuthRequest}, …) and returns them as one framed response.
 *
 * <p>Vanilla's {@code processServer} is not idempotent (it stamps the connection, hits the user
 * database and pushes the connection details), so a retry must never run it twice. Two guards make
 * the post idempotent: one in-flight task per connection GUID, which a concurrent retry joins
 * instead of duplicating, and a short-lived reply cache keyed by GUID that a retry after a lost
 * response replays. A cached reply is only honored for the {@link UdpConnection} instance that
 * produced it, so a reconnect on the same RakNet GUID can never receive a stale login reply. A
 * LoginPacket that still arrives over UDP for an already logged-in connection is dropped by {@code
 * LoginPacketDuplicateGuardPatch}.
 *
 * <p>The frame format is {@code [short packetId][int length][length bytes]} repeated; bodies are
 * the vanilla packet bytes after the 3-byte packet header. Login bodies are never logged: they
 * carry the password, cleartext as vanilla sends it over UDP.
 */
public final class StormTcpLogin {

    /** Username, password, version strings plus an int; vanilla clients send well under 1 KB. */
    public static final int MAX_LOGIN_BYTES = 4 * 1024;

    static final long TASK_TIMEOUT_SECONDS = 15;
    static final long REPLY_TTL_MILLIS = 60_000;
    static final int MAX_CACHED_REPLIES = 256;

    /** {@code RequestDataPacket.RequestType} ordinals; the enum is package-private. */
    static final byte REQUEST_TYPE_FULL_DATA = 2;

    static final byte REQUEST_TYPE_PART_DATA = 3;

    /** Thrown by {@link #process} when the body does not parse as a LoginPacket. */
    public static final class MalformedLoginException extends RuntimeException {
        MalformedLoginException(Throwable cause) {
            super("malformed login packet", cause);
        }
    }

    /** Thrown by {@link #process} when the connection already completed a login. */
    public static final class AlreadyLoggedInException extends RuntimeException {
        AlreadyLoggedInException() {
            super("connection already logged in");
        }
    }

    record Reply(UdpConnection connection, byte[] frames, long atMillis) {}

    private static final Map<Long, Reply> REPLIES = new ConcurrentHashMap<>();
    private static final Map<UdpConnection, Future<byte[]>> IN_FLIGHT = new ConcurrentHashMap<>();

    private StormTcpLogin() {}

    /**
     * HTTP pool thread. Returns the framed reply, replaying a cached one for a retry. Throws {@link
     * TimeoutException} if the main thread did not get to the task, {@link ExecutionException}
     * wrapping {@link MalformedLoginException} / {@link AlreadyLoggedInException} or an unexpected
     * failure inside vanilla's handler.
     */
    public static byte[] login(UdpConnection connection, byte[] loginPacket)
            throws TimeoutException, ExecutionException, InterruptedException {
        long guid = connection.getConnectedGUID();
        Reply cached = REPLIES.get(guid);
        if (cached != null && cached.connection() == connection) {
            return cached.frames();
        }
        // Keyed by connection identity, not GUID: RakNet keeps the GUID across a reconnect, and a
        // retry must never join the previous connection's task.
        Future<byte[]> future =
                IN_FLIGHT.computeIfAbsent(
                        connection,
                        c -> StormServerTaskQueue.submit(() -> process(connection, loginPacket)));
        try {
            return future.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            if (future.isDone()) {
                IN_FLIGHT.remove(connection, future);
            }
        }
    }

    /** Main thread. Runs the vanilla login handler and collects what it sent. */
    static byte[] process(UdpConnection connection, byte[] loginPacket) throws IOException {
        long guid = connection.getConnectedGUID();
        if (GameServer.udpEngine == null
                || GameServer.udpEngine.getActiveConnection(guid) != connection) {
            throw new AlreadyLoggedInException();
        }
        if (connection.getUserName() != null) {
            throw new AlreadyLoggedInException();
        }
        List<StormPacketDivert.Frame> frames;
        try (StormPacketDivert.Scope scope =
                StormPacketDivert.open(connection, PacketTypes.PacketType.values())) {
            LoginPacket packet = new LoginPacket();
            try {
                packet.parse(new ByteBufferReader(ByteBuffer.wrap(loginPacket)), connection);
            } catch (RuntimeException e) {
                throw new MalformedLoginException(e);
            }
            packet.processServer(PacketTypes.PacketType.Login, connection);
            frames = scope.takeFrames();
        }
        frames = collapseConnectionDetails(connection, frames);
        byte[] encoded = encode(frames);
        cache(guid, new Reply(connection, encoded, System.currentTimeMillis()));
        LOGGER.info(
                "Login over TCP processed for {} ({} frames, {} bytes)",
                connection.getIDStr(),
                frames.size(),
                encoded.length);
        return encoded;
    }

    /**
     * Vanilla splits a ConnectionDetails payload of 1 KB or more into {@code PartData} packets and
     * waits for ACKs that a TCP client never sends. Replace the parts with one {@code FullData}
     * frame built from the buffer vanilla just filled, and drop the pending transfer.
     */
    static List<StormPacketDivert.Frame> collapseConnectionDetails(
            UdpConnection connection, List<StormPacketDivert.Frame> frames) {
        byte detailsId = (byte) RequestDataPacket.RequestID.ConnectionDetails.ordinal();
        List<StormPacketDivert.Frame> out = new ArrayList<>(frames.size());
        boolean replaced = false;
        for (StormPacketDivert.Frame frame : frames) {
            byte[] payload = frame.payload();
            boolean detailsPart =
                    frame.type() == PacketTypes.PacketType.RequestData
                            && payload.length >= 2
                            && payload[0] == REQUEST_TYPE_PART_DATA
                            && payload[1] == detailsId;
            if (!detailsPart) {
                out.add(frame);
                continue;
            }
            if (replaced) {
                continue;
            }
            replaced = true;
            ByteBuffer details = RequestDataPacket.largeFileBb;
            int length = details == null ? 0 : details.position();
            byte[] full = new byte[2 + length];
            full[0] = REQUEST_TYPE_FULL_DATA;
            full[1] = detailsId;
            if (length > 0) {
                details.get(0, full, 2, length);
            }
            out.add(new StormPacketDivert.Frame(PacketTypes.PacketType.RequestData, full));
        }
        if (replaced) {
            RequestDataManager.getInstance().disconnect(connection);
        }
        return out;
    }

    static byte[] encode(List<StormPacketDivert.Frame> frames) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (StormPacketDivert.Frame frame : frames) {
            out.writeShort(frame.type().getId());
            out.writeInt(frame.payload().length);
            out.write(frame.payload());
        }
        out.flush();
        return bytes.toByteArray();
    }

    private static void cache(long guid, Reply reply) {
        if (REPLIES.size() >= MAX_CACHED_REPLIES) {
            sweep(reply.atMillis());
        }
        if (REPLIES.size() < MAX_CACHED_REPLIES) {
            REPLIES.put(guid, reply);
        }
    }

    /** Called from the game server tick; drops expired replies and those for dead connections. */
    @SubscribeEvent
    public static void onEveryOneMinute(EveryOneMinuteEvent event) {
        if (GameServer.server) {
            sweep(System.currentTimeMillis());
        }
    }

    static void sweep(long nowMillis) {
        for (Iterator<Map.Entry<Long, Reply>> it = REPLIES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Reply> entry = it.next();
            Reply reply = entry.getValue();
            boolean expired = nowMillis - reply.atMillis() > REPLY_TTL_MILLIS;
            boolean dead =
                    GameServer.udpEngine == null
                            || GameServer.udpEngine.getActiveConnection(entry.getKey())
                                    != reply.connection();
            if (expired || dead) {
                it.remove();
            }
        }
    }

    /**
     * Advice entry for {@code LoginPacket.processServer}: {@code true} drops a LoginPacket for a
     * connection that already logged in. Vanilla clients send exactly one Login per connection, so
     * this only ever fires for a Storm client's UDP fallback racing its TCP post, or for a peer
     * replaying the packet on purpose.
     */
    public static boolean isDuplicateLogin(@Nullable Object connection) {
        try {
            if (connection instanceof UdpConnection udp && udp.getUserName() != null) {
                LOGGER.warn(
                        "Dropping duplicate LoginPacket from {} (already logged in as {})",
                        udp.getIDStr(),
                        udp.getUserName());
                return true;
            }
        } catch (Throwable t) {
            LOGGER.error("Duplicate-login guard failed; letting vanilla handle the packet", t);
        }
        return false;
    }

    /** Test hook. */
    static void reset() {
        REPLIES.clear();
        IN_FLIGHT.clear();
    }

    static int cachedReplies() {
        return REPLIES.size();
    }
}
