package io.pzstorm.storm.connection;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.client.StormLoginOverTcp;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameClient;
import zombie.network.PacketTypes;

/**
 * Captures outgoing vanilla packets at {@code PacketTypes.PacketType.send(IConnection)} so their
 * payload can travel over the game-port TCP channel instead of RakNet. The vanilla code that builds
 * the packet ({@code startPacket} → {@code doPacket} → {@code write}) runs untouched; only the
 * final send is swapped for a copy of the buffer plus {@code cancelPacket()}.
 *
 * <p>Two triggers divert a send, in this order:
 *
 * <ol>
 *   <li>A {@link Scope} opened on the current thread for that connection and packet type. Both JVMs
 *       use this for request/reply exchanges that vanilla drives with nested sends (the Lua
 *       checksum state machine).
 *   <li>Server only: a {@code LoginQueueRequest} to a connection whose {@link
 *       StormLoginQueueMailbox} is enabled. Those sends originate from the login-queue update loop
 *       and other connections' {@code loadNextPlayer}, so no scope can enclose them.
 *   <li>Client only: the {@code Login} packet, handed to {@link StormLoginOverTcp} while the TCP
 *       channel can still be established.
 * </ol>
 *
 * <p>Client only, and not a diversion: the packets in {@link #CONNECT_CRITICAL} skip vanilla's
 * per-type send limiter. That limiter answers an exceeded budget with {@code cancelPacket()}, which
 * for a one-shot connection-phase packet means a join that hangs with nothing logged.
 *
 * <p>Fail-soft: any problem reading the buffer (renamed field, unexpected header) makes {@link
 * #tryDivert} return {@code false}, and the vanilla UDP send runs exactly as before.
 */
public final class StormPacketDivert {

    /** {@code putByte(134) + putShort(id)} written by {@code PacketType.doPacket}. */
    static final int HEADER_LENGTH = 3;

    static final byte HEADER_MARKER = (byte) 134;

    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();

    private static final Set<PacketTypes.PacketType> CONNECT_CRITICAL =
            EnumSet.of(
                    PacketTypes.PacketType.Login,
                    PacketTypes.PacketType.PlayerConnect,
                    PacketTypes.PacketType.LoginQueueRequest,
                    PacketTypes.PacketType.LoginQueueDone,
                    PacketTypes.PacketType.Checksum,
                    PacketTypes.PacketType.RequestData);

    private static volatile boolean initAttempted;
    private static volatile boolean ready;
    private static Field bufferField;

    private StormPacketDivert() {}

    /**
     * Thread-local capture window. Every packet of one of {@code types} sent to {@code connection}
     * from this thread while the scope is open is captured instead of sent.
     */
    public static final class Scope implements AutoCloseable {

        private final UdpConnection connection;
        private final Set<PacketTypes.PacketType> types;
        private final List<Frame> captured = new ArrayList<>();
        private final @Nullable Scope outer;

        private Scope(
                UdpConnection connection,
                Set<PacketTypes.PacketType> types,
                @Nullable Scope outer) {
            this.connection = connection;
            this.types = types;
            this.outer = outer;
        }

        boolean covers(UdpConnection candidate, PacketTypes.PacketType type) {
            return connection == candidate && types.contains(type);
        }

        /** Drains and returns every payload captured so far (header stripped), oldest first. */
        public List<byte[]> take() {
            List<byte[]> out = new ArrayList<>(captured.size());
            for (Frame frame : captured) {
                out.add(frame.payload());
            }
            captured.clear();
            return out;
        }

        /** Like {@link #take()} but keeps each payload paired with its packet type. */
        public List<Frame> takeFrames() {
            List<Frame> out = new ArrayList<>(captured);
            captured.clear();
            return out;
        }

        /** The oldest captured payload, or {@code null} when nothing is pending. */
        public @Nullable byte[] takeOne() {
            return captured.isEmpty() ? null : captured.remove(0).payload();
        }

        public int pending() {
            return captured.size();
        }

        @Override
        public void close() {
            if (ACTIVE.get() == this) {
                if (outer == null) {
                    ACTIVE.remove();
                } else {
                    ACTIVE.set(outer);
                }
            }
        }
    }

    /** One captured packet: its type and its body after the 3-byte header. */
    public record Frame(PacketTypes.PacketType type, byte[] payload) {}

    public static Scope open(UdpConnection connection, PacketTypes.PacketType... types) {
        Set<PacketTypes.PacketType> set = EnumSet.noneOf(PacketTypes.PacketType.class);
        for (PacketTypes.PacketType type : types) {
            set.add(type);
        }
        Scope scope = new Scope(connection, set, ACTIVE.get());
        ACTIVE.set(scope);
        return scope;
    }

    /**
     * Advice entry point for {@code PacketType.send}. Returns {@code true} when the packet was
     * captured (and the connection's packet buffer released), {@code false} to let vanilla send it.
     */
    public static boolean tryDivert(Object type, Object connection) {
        try {
            if (!(type instanceof PacketTypes.PacketType packetType)
                    || !(connection instanceof UdpConnection udp)) {
                return false;
            }
            Scope scope = ACTIVE.get();
            if (scope != null && scope.covers(udp, packetType)) {
                byte[] payload = capture(packetType, udp);
                if (payload == null) {
                    return false;
                }
                udp.cancelPacket();
                scope.captured.add(new Frame(packetType, payload));
                return true;
            }
            if (packetType == PacketTypes.PacketType.LoginQueueRequest
                    && StormLoginQueueMailbox.isEnabled(udp.getConnectedGUID())) {
                byte[] payload = capture(packetType, udp);
                if (payload == null) {
                    return false;
                }
                udp.cancelPacket();
                StormLoginQueueMailbox.offer(udp.getConnectedGUID(), payload);
                return true;
            }
            if (!GameClient.client) {
                return false;
            }
            if (packetType == PacketTypes.PacketType.Login && StormLoginOverTcp.wantsLogin(udp)) {
                byte[] payload = capture(packetType, udp);
                if (payload == null) {
                    return false;
                }
                udp.cancelPacket();
                StormLoginOverTcp.hold(udp, payload);
                return true;
            }
            if (CONNECT_CRITICAL.contains(packetType)) {
                udp.endPacket(
                        packetType.packetPriority,
                        packetType.packetReliability,
                        packetType.orderingChannel);
                return true;
            }
            return false;
        } catch (Throwable t) {
            LOGGER.error("Packet divert failed; sending over UDP", t);
            return false;
        }
    }

    /**
     * Re-issues a captured payload as a real packet on {@code connection}. Used when a TCP-mode
     * client turns out to have fallen back to UDP with messages still pending.
     */
    public static void sendOverUdp(
            UdpConnection connection, PacketTypes.PacketType type, byte[] payload) {
        zombie.core.network.ByteBufferWriter writer = connection.startPacket();
        type.doPacket(writer);
        writer.bb.put(payload);
        if (!tryDivert(type, connection)) {
            type.send(connection);
        }
    }

    /** Copy of the packet body after the 3-byte header, or {@code null} if it cannot be trusted. */
    static @Nullable byte[] capture(PacketTypes.PacketType type, UdpConnection connection) {
        if (!init()) {
            return null;
        }
        ByteBuffer buffer;
        try {
            buffer = (ByteBuffer) bufferField.get(connection);
        } catch (IllegalAccessException e) {
            return null;
        }
        int end = buffer.position();
        if (end < HEADER_LENGTH
                || buffer.get(0) != HEADER_MARKER
                || buffer.getShort(1) != type.getId()) {
            return null;
        }
        byte[] payload = new byte[end - HEADER_LENGTH];
        buffer.get(HEADER_LENGTH, payload);
        return payload;
    }

    private static boolean init() {
        if (initAttempted) {
            return ready;
        }
        synchronized (StormPacketDivert.class) {
            if (initAttempted) {
                return ready;
            }
            try {
                bufferField = UdpConnection.class.getDeclaredField("bb");
                bufferField.setAccessible(true);
                ready = true;
            } catch (Throwable t) {
                LOGGER.error(
                        "Packet divert: reflection against UdpConnection failed — TCP diversion"
                                + " of vanilla packets is disabled, UDP sends run",
                        t);
            }
            initAttempted = true;
            return ready;
        }
    }

    // test hook
    static @Nullable Scope activeScope() {
        return ACTIVE.get();
    }
}
