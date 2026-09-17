package io.pzstorm.storm.connection;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.EveryOneMinuteEvent;
import io.pzstorm.storm.event.packet.LoginQueueDonePacketEvent;
import io.pzstorm.storm.event.packet.QueuePacketEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;
import zombie.network.PacketTypes;

/**
 * Server-side per-connection outbox for login-queue messages ({@code ConnectionImmediate} / {@code
 * PlaceInQueue}) of clients that asked for the queue over TCP. {@link StormPacketDivert} drops each
 * such send here instead of RakNet; {@code GamePortLoginQueueEndpoints} hands them to the client's
 * poll requests.
 *
 * <p>A box lives from the TCP login-queue request until the client's {@code LoginQueueDone} (TCP or
 * UDP), until a UDP {@code LoginQueueRequest} shows the client fell back — any messages captured
 * meanwhile are replayed over UDP so the fallback sees them — or until the connection is gone.
 */
public final class StormLoginQueueMailbox {

    /** A queued client receives one place update per 20 s; anything beyond this is stale. */
    static final int MAX_PENDING = 8;

    private static final Map<Long, Box> BOXES = new ConcurrentHashMap<>();

    private static final class Box {
        final ArrayDeque<byte[]> pending = new ArrayDeque<>();
        boolean closed;
    }

    private StormLoginQueueMailbox() {}

    public static void enable(long guid) {
        BOXES.computeIfAbsent(guid, g -> new Box());
    }

    /** Closes the box and returns whatever was still pending, oldest first. */
    public static List<byte[]> disable(long guid) {
        Box box = BOXES.remove(guid);
        if (box == null) {
            return List.of();
        }
        synchronized (box) {
            box.closed = true;
            List<byte[]> leftover = new ArrayList<>(box.pending);
            box.pending.clear();
            box.notifyAll();
            return leftover;
        }
    }

    public static boolean isEnabled(long guid) {
        return BOXES.containsKey(guid);
    }

    public static void offer(long guid, byte[] message) {
        Box box = BOXES.get(guid);
        if (box == null) {
            return;
        }
        synchronized (box) {
            if (box.closed) {
                return;
            }
            if (box.pending.size() >= MAX_PENDING) {
                box.pending.pollFirst();
            }
            box.pending.addLast(message);
            box.notifyAll();
        }
    }

    /**
     * Oldest pending message, waiting up to {@code waitMillis} for one to arrive. {@code null} when
     * nothing arrived in time or the box is not enabled.
     */
    public static @Nullable byte[] poll(long guid, long waitMillis) throws InterruptedException {
        Box box = BOXES.get(guid);
        if (box == null) {
            return null;
        }
        long deadline = System.currentTimeMillis() + waitMillis;
        synchronized (box) {
            while (box.pending.isEmpty()) {
                long remaining = deadline - System.currentTimeMillis();
                if (box.closed || remaining <= 0) {
                    return null;
                }
                box.wait(remaining);
            }
            return box.pending.pollFirst();
        }
    }

    public static int pendingCount(long guid) {
        Box box = BOXES.get(guid);
        if (box == null) {
            return 0;
        }
        synchronized (box) {
            return box.pending.size();
        }
    }

    public static int size() {
        return BOXES.size();
    }

    public static void reset() {
        for (Long guid : new ArrayList<>(BOXES.keySet())) {
            disable(guid);
        }
    }

    /**
     * A {@code LoginQueueRequest} arriving over UDP means the client gave up on TCP. The vanilla
     * handler has already run (this event fires after {@code processServer}), so its reply and any
     * earlier place updates sit in the box; close it and put them on the wire.
     */
    @SubscribeEvent
    public static void onQueuePacket(QueuePacketEvent event) {
        if (!GameServer.server) {
            return;
        }
        UdpConnection connection = event.connection;
        List<byte[]> leftover = disable(connection.getConnectedGUID());
        if (leftover.isEmpty()) {
            return;
        }
        LOGGER.info(
                "Login queue: {} fell back to UDP; replaying {} pending message(s)",
                connection.getUserName(),
                leftover.size());
        for (byte[] message : leftover) {
            try {
                StormPacketDivert.sendOverUdp(
                        connection, PacketTypes.PacketType.LoginQueueRequest, message);
            } catch (Throwable t) {
                LOGGER.error("Login queue: UDP replay failed", t);
            }
        }
    }

    @SubscribeEvent
    public static void onLoginQueueDone(LoginQueueDonePacketEvent event) {
        if (!GameServer.server) {
            return;
        }
        disable(event.connection.getConnectedGUID());
    }

    /** Drops boxes whose connection disappeared without a done or fallback. */
    @SubscribeEvent
    public static void onEveryOneMinute(EveryOneMinuteEvent event) {
        if (!GameServer.server || GameServer.udpEngine == null) {
            return;
        }
        for (Iterator<Long> it = BOXES.keySet().iterator(); it.hasNext(); ) {
            long guid = it.next();
            if (GameServer.udpEngine.getActiveConnection(guid) == null) {
                disable(guid);
            }
        }
    }
}
