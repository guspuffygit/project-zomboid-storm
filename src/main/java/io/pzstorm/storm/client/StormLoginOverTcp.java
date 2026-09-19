package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.connection.StormPacketDivert;
import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.OnFETickEvent;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import zombie.Lua.LuaEventManager;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameClient;
import zombie.network.PacketTypes;
import zombie.network.packets.service.AccessDeniedPacket;

/**
 * Client half of LoginPacket over the game-port TCP channel. Vanilla fires the Login once over UDP
 * from {@code UdpEngine.connected()} with no retry; if that datagram is lost the client sits on
 * "Connecting…" until the server reaps it. Here the packet is held at {@code PacketType.send} while
 * the TCP handshake completes, posted to {@code POST /storm/game/login} with retries, and the
 * frames the server answered with are fed into {@link GameClient#addIncoming} on the main thread
 * exactly as if they had arrived over UDP.
 *
 * <p>Every failure releases the held packet over UDP, so the worst case is vanilla's own behaviour
 * delayed by at most {@link #FALLBACK_DEADLINE_MILLIS}. The server drops a second Login on a
 * connection that already logged in, which makes a TCP-then-UDP retry harmless.
 *
 * <p>Threads: {@link #wantsLogin}/{@link #hold} run on whichever thread vanilla sends the Login
 * (RakNet's receive thread), {@link #onSessionEstablished}/{@link #tick} on the channel watcher,
 * and {@link #onFETick} on the game main thread. All state is guarded by the class monitor; HTTP
 * I/O happens outside it.
 */
public final class StormLoginOverTcp {

    static final long HOLD_BUDGET_MILLIS = 2_000;
    static final long FALLBACK_DEADLINE_MILLIS = 8_000;
    static final int MAX_POST_ATTEMPTS = 2;
    static final String PATH = "/storm/game/login";

    /** One packet from the server's login reply: the vanilla id and body after the header. */
    record Frame(short id, byte[] payload) {}

    private static @Nullable UdpConnection heldConnection;
    private static @Nullable byte[] heldPayload;
    private static long heldAt;
    private static boolean posting;
    private static boolean fallbackRequested;
    private static boolean resendingOverUdp;
    private static @Nullable List<Frame> pendingFrames;
    private static boolean malformedReply;

    private StormLoginOverTcp() {}

    /** Called from the divert advice on the sending thread. */
    public static synchronized boolean wantsLogin(UdpConnection connection) {
        return !resendingOverUdp
                && GameClient.connection == connection
                && StormTcpChannel.mayEstablish();
    }

    /** Takes ownership of a captured Login payload; the caller already cancelled the packet. */
    public static synchronized void hold(UdpConnection connection, byte[] payload) {
        heldConnection = connection;
        heldPayload = payload;
        heldAt = System.currentTimeMillis();
        posting = false;
        fallbackRequested = false;
        pendingFrames = null;
        malformedReply = false;
        LOGGER.info("Holding LoginPacket for the Storm TCP channel");
    }

    /**
     * Channel watcher thread, every poll while a session exists. Posts a held Login once; a no-op
     * when nothing is held, a post is running, or the outcome is waiting for the main thread.
     */
    public static void onSessionEstablished() {
        UdpConnection connection;
        byte[] payload;
        synchronized (StormLoginOverTcp.class) {
            if (heldPayload == null
                    || posting
                    || pendingFrames != null
                    || fallbackRequested
                    || malformedReply) {
                return;
            }
            connection = heldConnection;
            payload = heldPayload;
            posting = true;
        }
        List<Frame> frames = null;
        String failure = "no attempts";
        for (int attempt = 1; attempt <= MAX_POST_ATTEMPTS && frames == null; attempt++) {
            try {
                HttpRequest.Builder builder = StormTcpChannel.authenticatedRequest(PATH);
                if (builder == null) {
                    failure = "session dropped";
                    break;
                }
                HttpResponse<byte[]> response =
                        StormTcpChannel.send(
                                builder.header("Content-Type", "application/octet-stream")
                                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                                        .build());
                int status = response.statusCode();
                if (status == 200) {
                    frames = decode(response.body());
                } else if (status == 409) {
                    // The server already ran this login (an earlier post landed); its reply
                    // comes over UDP as usual, so there is nothing left to deliver.
                    LOGGER.info("Login over TCP: server reports the connection already logged in");
                    synchronized (StormLoginOverTcp.class) {
                        if (heldConnection == connection) {
                            clear();
                        }
                    }
                    return;
                } else if (status == 503) {
                    failure = "503 server busy";
                } else {
                    failure = status + " " + new String(response.body()).trim();
                    break;
                }
            } catch (MalformedReplyException e) {
                failure = "malformed reply";
                synchronized (StormLoginOverTcp.class) {
                    if (heldConnection == connection) {
                        malformedReply = true;
                        posting = false;
                    }
                }
                LOGGER.error("Login over TCP returned an undecodable reply", e);
                return;
            } catch (Exception e) {
                failure = e.toString();
            }
        }
        synchronized (StormLoginOverTcp.class) {
            if (heldConnection != connection) {
                return;
            }
            posting = false;
            if (frames != null) {
                pendingFrames = frames;
                LOGGER.info("Login over TCP succeeded ({} reply frames)", frames.size());
            } else {
                fallbackRequested = true;
                LOGGER.warn(
                        "Login over TCP failed ({}); sending the LoginPacket over UDP", failure);
            }
        }
    }

    /** Channel watcher thread, every poll. Hard deadline in case the main thread never drains. */
    public static void tick(long nowMillis) {
        UdpConnection connection;
        byte[] payload;
        synchronized (StormLoginOverTcp.class) {
            if (heldPayload == null
                    || posting
                    || pendingFrames != null
                    || nowMillis - heldAt < FALLBACK_DEADLINE_MILLIS + HOLD_BUDGET_MILLIS) {
                return;
            }
            connection = heldConnection;
            payload = heldPayload;
            resendingOverUdp = true;
            clear();
        }
        LOGGER.warn("Login reply never drained; sending the LoginPacket over UDP");
        resend(connection, payload);
    }

    @SubscribeEvent
    public static void onFETick(OnFETickEvent event) {
        drain(System.currentTimeMillis());
    }

    /** Main thread. Delivers the reply, or releases the held Login over UDP. */
    static void drain(long nowMillis) {
        UdpConnection connection;
        byte[] payload;
        List<Frame> frames;
        boolean malformed;
        synchronized (StormLoginOverTcp.class) {
            if (heldPayload == null) {
                return;
            }
            if (GameClient.connection != heldConnection) {
                LOGGER.info("Held LoginPacket discarded; the game connection changed");
                clear();
                return;
            }
            frames = pendingFrames;
            malformed = malformedReply;
            boolean release =
                    fallbackRequested
                            || (!posting
                                    && !StormTcpChannel.isEstablished()
                                    && nowMillis - heldAt >= HOLD_BUDGET_MILLIS)
                            || (!posting && nowMillis - heldAt >= FALLBACK_DEADLINE_MILLIS);
            if (frames == null && !malformed && !release) {
                return;
            }
            connection = heldConnection;
            payload = heldPayload;
            if (frames == null && !malformed) {
                resendingOverUdp = true;
            }
            clear();
        }
        if (malformed) {
            LuaEventManager.triggerEvent("OnConnectFailed", "Storm: malformed login reply");
            return;
        }
        if (frames == null) {
            if (!StormTcpChannel.isEstablished()) {
                LOGGER.info("Storm TCP channel not established; sending the LoginPacket over UDP");
            }
            resend(connection, payload);
            return;
        }
        deliver(connection, frames);
    }

    private static void deliver(UdpConnection connection, List<Frame> frames) {
        for (Frame frame : frames) {
            ByteBufferReader reader = new ByteBufferReader(ByteBuffer.wrap(frame.payload()));
            try {
                if (GameClient.connection == null) {
                    if (frame.id() == PacketTypes.PacketType.AccessDenied.getId()) {
                        AccessDeniedPacket denied = new AccessDeniedPacket();
                        denied.parse(reader, null);
                        denied.processClientLoading(null);
                    }
                    continue;
                }
                GameClient.instance.addIncoming(frame.id(), reader);
            } catch (Throwable t) {
                LOGGER.error("Failed to deliver login reply frame id={}", frame.id(), t);
            }
        }
    }

    private static void resend(UdpConnection connection, byte[] payload) {
        try {
            StormPacketDivert.sendOverUdp(connection, PacketTypes.PacketType.Login, payload);
        } catch (Throwable t) {
            LOGGER.error("Failed to resend the LoginPacket over UDP", t);
        } finally {
            synchronized (StormLoginOverTcp.class) {
                resendingOverUdp = false;
            }
        }
    }

    private static void clear() {
        heldConnection = null;
        heldPayload = null;
        posting = false;
        fallbackRequested = false;
        pendingFrames = null;
        malformedReply = false;
    }

    /** Called by the channel watcher when the game connection drops. */
    public static synchronized void reset() {
        clear();
        resendingOverUdp = false;
    }

    static final class MalformedReplyException extends IOException {
        MalformedReplyException(String message) {
            super(message);
        }
    }

    /** Server frame format: {@code [short id][int length][bytes]} repeated. */
    static List<Frame> decode(byte[] body) throws IOException {
        List<Frame> frames = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(wrap(body))) {
            while (in.available() > 0) {
                short id = in.readShort();
                int length = in.readInt();
                if (length < 0 || length > in.available()) {
                    throw new MalformedReplyException("frame length " + length + " out of range");
                }
                byte[] payload = new byte[length];
                in.readFully(payload);
                frames.add(new Frame(id, payload));
            }
        } catch (java.io.EOFException e) {
            throw new MalformedReplyException("truncated frame");
        }
        return frames;
    }

    private static InputStream wrap(byte[] body) {
        return new java.io.ByteArrayInputStream(body);
    }

    static synchronized boolean isHolding() {
        return heldPayload != null;
    }
}
