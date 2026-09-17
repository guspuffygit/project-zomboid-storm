package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jetbrains.annotations.Nullable;
import zombie.core.network.ByteBufferReader;
import zombie.gameStates.GameLoadingState;
import zombie.network.GameClient;
import zombie.network.packets.connection.QueuePacket;

/**
 * Client half of the login queue over TCP. {@link #tryRequest} replaces the UDP {@code
 * LoginQueueRequest}; a poller thread then fetches the server's {@code ConnectionImmediate} /
 * {@code PlaceInQueue} messages, and {@link #drainOnMainThread} applies them from {@code
 * LoadingQueueState.update()} with the vanilla packet class — the same thread and code path the UDP
 * packets take. {@link #tryDone} replaces {@code LoginQueueDone}; the HTTP 200 stands in for the
 * vanilla echo that a lost datagram used to stall on.
 *
 * <p>Fail-soft: three consecutive poll failures flag a fallback, and the next main-thread drain
 * re-issues the vanilla UDP request (the advice lets that one through). The server replays any
 * message it captured meanwhile, so a mid-queue fallback keeps the client's place.
 */
public final class StormLoginQueueOverTcp {

    private static final String PATH = "/storm/game/login-queue";
    private static final long POLL_INTERVAL_MILLIS = 1000;
    private static final Duration POLL_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final ConcurrentLinkedQueue<byte[]> PENDING = new ConcurrentLinkedQueue<>();

    private static volatile boolean polling;
    private static volatile boolean granted;
    private static volatile boolean fallbackRequested;
    private static volatile boolean resendingOverUdp;
    private static volatile @Nullable Thread poller;

    private StormLoginQueueOverTcp() {}

    /** From {@code GameClient.sendLoginQueueRequest()} on the main thread, once per connect. */
    public static boolean tryRequest() {
        if (resendingOverUdp) {
            return false;
        }
        try {
            if (!StormTcpChannel.isEstablished() || GameClient.connection == null) {
                return false;
            }
            stopPoller();
            PENDING.clear();
            granted = false;
            fallbackRequested = false;
            HttpRequest.Builder builder = StormTcpChannel.authenticatedRequest(PATH);
            if (builder == null) {
                return false;
            }
            HttpResponse<byte[]> response =
                    StormTcpChannel.send(builder.POST(HttpRequest.BodyPublishers.noBody()).build());
            if (response.statusCode() == 200) {
                PENDING.add(response.body());
            } else if (response.statusCode() != 204) {
                LOGGER.info(
                        "TCP login queue request returned {}; falling back to UDP",
                        response.statusCode());
                return false;
            }
            startPoller();
            LOGGER.info("Login queue request sent over TCP");
            return true;
        } catch (Throwable t) {
            LOGGER.info("TCP login queue request failed ({}); falling back to UDP", t.toString());
            return false;
        }
    }

    /** From {@code LoadingQueueState.update()} on the main thread. */
    public static void drainOnMainThread() {
        try {
            byte[] message;
            while ((message = PENDING.poll()) != null) {
                apply(message);
            }
            if (fallbackRequested && !granted) {
                fallbackRequested = false;
                resendOverUdp();
            }
        } catch (Throwable t) {
            LOGGER.error("Login queue over TCP: drain failed", t);
        }
    }

    /** From {@code GameClient.sendLoginQueueDone(long)} on the loader thread. */
    public static boolean tryDone(long loadingMillis) {
        try {
            if (!StormTcpChannel.isEstablished() || GameClient.connection == null) {
                return false;
            }
            HttpRequest.Builder builder = StormTcpChannel.authenticatedRequest(PATH + "/done");
            if (builder == null) {
                return false;
            }
            HttpResponse<byte[]> response =
                    StormTcpChannel.send(
                            builder.header("Content-Type", "application/json")
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    "{\"loadingMillis\":" + loadingMillis + "}"))
                                    .build());
            if (response.statusCode() != 200) {
                LOGGER.info(
                        "TCP login queue done returned {}; falling back to UDP",
                        response.statusCode());
                return false;
            }
            stopPoller();
            GameLoadingState.Done();
            LOGGER.info("Login queue done acknowledged over TCP");
            return true;
        } catch (Throwable t) {
            LOGGER.info("TCP login queue done failed ({}); falling back to UDP", t.toString());
            return false;
        }
    }

    static void apply(byte[] message) {
        try {
            QueuePacket packet = new QueuePacket();
            packet.parse(new ByteBufferReader(ByteBuffer.wrap(message)), GameClient.connection);
            packet.processClientLoading(GameClient.connection);
            if (isConnectionImmediate(message)) {
                granted = true;
                stopPoller();
            }
        } catch (Throwable t) {
            LOGGER.error("Login queue over TCP: bad message from server", t);
        }
    }

    /** First byte is the {@code QueuePacket.MessageType} ordinal ({@code putEnum}). */
    static boolean isConnectionImmediate(byte[] message) {
        if (message.length == 0) {
            return false;
        }
        QueuePacket.MessageType[] types = QueuePacket.MessageType.values();
        int ordinal = message[0] & 0xFF;
        return ordinal < types.length
                && types[ordinal] == QueuePacket.MessageType.ConnectionImmediate;
    }

    private static void resendOverUdp() {
        LOGGER.warn("Login queue over TCP failed; falling back to UDP");
        resendingOverUdp = true;
        try {
            GameClient.instance.sendLoginQueueRequest();
        } finally {
            resendingOverUdp = false;
        }
    }

    private static void startPoller() {
        polling = true;
        Thread thread = new Thread(StormLoginQueueOverTcp::pollLoop, "storm-login-queue-tcp");
        thread.setDaemon(true);
        poller = thread;
        thread.start();
    }

    private static void stopPoller() {
        polling = false;
        Thread thread = poller;
        poller = null;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
    }

    private static void pollLoop() {
        int failures = 0;
        while (polling && GameClient.connection != null) {
            HttpRequest.Builder builder = StormTcpChannel.authenticatedRequest(PATH + "?wait=0");
            if (builder == null) {
                return;
            }
            try {
                HttpResponse<byte[]> response =
                        StormTcpChannel.send(builder.timeout(POLL_REQUEST_TIMEOUT).GET().build());
                switch (response.statusCode()) {
                    case 200 -> {
                        PENDING.add(response.body());
                        failures = 0;
                    }
                    case 204 -> failures = 0;
                    case 410 -> {
                        // The server closed the box; if we were not granted, something ate it.
                        if (!granted) {
                            fallbackRequested = true;
                        }
                        return;
                    }
                    default -> failures++;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                failures++;
            }
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                fallbackRequested = true;
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // test hooks
    static void resetForTests() {
        stopPoller();
        PENDING.clear();
        granted = false;
        fallbackRequested = false;
        resendingOverUdp = false;
    }

    static boolean isGranted() {
        return granted;
    }
}
