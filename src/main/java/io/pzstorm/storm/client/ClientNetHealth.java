package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.Collections;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.ZNetStatistics;
import zombie.debug.DebugLog;
import zombie.network.GameClient;

/**
 * Records the client's own view of its RakNet connection so a "Send Logs" bundle can tell a
 * black-holed path from a dead link or a dead process. A daemon thread samples the connection every
 * {@link #POLL_MS} into a short ring. It logs one line at connect (MTU, transport, relay flag,
 * local interface MTUs) and dumps the ring when the connection ends or when almost nothing arrives
 * for {@link #QUIET_SAMPLES} samples in a row.
 *
 * <p>Reads counters only and every poll is fenced by a catch-all, so a game update that changes
 * these surfaces degrades to one warning.
 */
public final class ClientNetHealth {

    static final long POLL_MS = 5_000L;

    static final int RING_SAMPLES = 24;

    /** A full-size server datagram is ~1.4 KB, so a sample below this received none. */
    static final long QUIET_BYTES = 1_000L;

    static final int QUIET_SAMPLES = 4;

    private static final String TAG = "[StormNetHealth] ";

    private static Thread sampler;

    private ClientNetHealth() {}

    /** Idempotent; called reflectively by {@code StormLauncher} on client JVMs. */
    public static synchronized void start() {
        if (sampler != null) {
            return;
        }
        sampler = new Thread(ClientNetHealth::run, "StormNetHealth");
        sampler.setDaemon(true);
        sampler.start();
    }

    private static void run() {
        ArrayDeque<String> ring = new ArrayDeque<>();
        UdpConnection tracked = null;
        long lastReceived = 0L;
        long lastSent = 0L;
        int quiet = 0;
        boolean quietDumped = false;
        boolean warned = false;
        while (true) {
            try {
                UdpConnection connection = GameClient.client ? GameClient.connection : null;
                ZNetStatistics stats = connection == null ? null : connection.getStatistics();
                if (stats == null) {
                    if (tracked != null) {
                        dump("connection ended", ring);
                    }
                    tracked = null;
                } else {
                    if (connection != tracked) {
                        tracked = connection;
                        ring.clear();
                        quiet = 0;
                        quietDumped = false;
                        lastReceived = stats.totalActualBytesReceived;
                        lastSent = stats.totalActualBytesSent;
                        emit(
                                "connected: mtu="
                                        + connection.getMTUSize()
                                        + " type="
                                        + connection.getConnectionType()
                                        + " relay="
                                        + GameClient.useSteamRelay
                                        + " interfaces=["
                                        + interfaces()
                                        + "]");
                    }
                    long received = stats.totalActualBytesReceived - lastReceived;
                    long sent = stats.totalActualBytesSent - lastSent;
                    lastReceived = stats.totalActualBytesReceived;
                    lastSent = stats.totalActualBytesSent;
                    if (ring.size() == RING_SAMPLES) {
                        ring.removeFirst();
                    }
                    ring.addLast(
                            System.currentTimeMillis()
                                    + " rx="
                                    + received
                                    + " tx="
                                    + sent
                                    + " ping="
                                    + connection.getLastPing()
                                    + " resendMsgs="
                                    + stats.messagesInResendBuffer
                                    + " resendBytes="
                                    + stats.bytesInResendBuffer
                                    + " loss="
                                    + stats.packetlossLastSecond
                                    + " full="
                                    + connection.isFullyConnected());
                    quiet = received < QUIET_BYTES ? quiet + 1 : 0;
                    if (quiet >= QUIET_SAMPLES && !quietDumped) {
                        quietDumped = true;
                        dump("receiving under " + QUIET_BYTES + " bytes per sample", ring);
                    }
                }
                warned = false;
            } catch (Throwable t) {
                if (!warned) {
                    warned = true;
                    LOGGER.warn("Client net health poll failed", t);
                }
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static String interfaces() {
        StringBuilder out = new StringBuilder();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(nic.getName()).append(" mtu ").append(nic.getMTU());
                if (nic.isPointToPoint()) {
                    out.append(" p2p");
                }
                if (nic.isVirtual()) {
                    out.append(" virtual");
                }
            }
        } catch (Throwable t) {
            out.append("err");
        }
        return out.toString();
    }

    private static void dump(String reason, ArrayDeque<String> ring) {
        StringBuilder text = new StringBuilder(reason).append(", last ").append(ring.size());
        text.append(" samples (").append(POLL_MS / 1000L).append("s apart):");
        for (String sample : ring) {
            text.append("\n    ").append(sample);
        }
        emit(text.toString());
    }

    private static void emit(String message) {
        LOGGER.info(TAG + message);
        try {
            DebugLog.log(TAG + message);
        } catch (Throwable ignored) {
            // vanilla logger not ready; the Storm log line above already landed
        }
    }
}
