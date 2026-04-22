package io.pzstorm.storm.liveserver;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import org.junit.jupiter.api.Assertions;
import zombie.core.Core;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.RakNetPeerInterface;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.random.RandStandard;
import zombie.core.znet.SteamUtils;
import zombie.network.GameClient;
import zombie.network.PacketTypes;
import zombie.network.ZomboidNetData;

/**
 * Test-only helper that drives one logical RakNet client through the full Project Zomboid login
 * handshake against a live dedicated server.
 *
 * <p>Multiple {@link LiveServerClient} instances share a single process-wide {@link UdpEngine}
 * because RakNet's native layer does not support multiple peers in the same JVM. A single peer,
 * however, can hold many outgoing connections; each {@link LiveServerClient} owns one {@link
 * UdpConnection} on the shared engine.
 *
 * <p>The login handshake follows this sequence:
 *
 * <ol>
 *   <li>Send {@code Login} + {@code LoginQueueRequest}
 *   <li>Wait for {@code LoginQueueRequest} response (QueuePacket with ConnectionImmediate)
 *   <li>Send {@code LoginQueueDone}
 *   <li>Send {@code LoadPlayerProfile} request for index 0
 *   <li>Wait for profile response; request next index or finish when {@code isExist=false}
 *   <li>Send {@code PlayerConnect}
 *   <li>Wait for {@code ConnectedPlayer} — server considers this connection fully connected
 * </ol>
 */
public final class LiveServerClient implements AutoCloseable {

    private static final int SHARED_MAX_CONNECTIONS = 8;

    private static boolean nativesInitialized = false;
    private static SharedEngine sharedEngine;

    @Getter private final String username;
    private final String password;
    private final int authType;

    @Getter private UdpConnection connection;
    @Getter private boolean fullyConnected;

    public LiveServerClient(String username, String password) {
        this(username, password, 1);
    }

    public LiveServerClient(String username, String password, int authType) {
        this.username = username;
        this.password = password;
        this.authType = authType;
    }

    public static synchronized void initClientNativesOnce() {
        if (nativesInitialized) {
            return;
        }
        System.clearProperty("zomboid.steam");
        SteamUtils.init();
        RandStandard.INSTANCE.init();
        RakNetPeerInterface.init();
        nativesInitialized = true;
    }

    public static synchronized void shutdownSharedEngine() {
        if (sharedEngine != null) {
            try {
                sharedEngine.Shutdown();
            } catch (Throwable ignored) {
            }
            sharedEngine = null;
        }
    }

    private static synchronized SharedEngine ensureSharedEngine() throws Exception {
        if (sharedEngine == null) {
            GameClient.client = true;
            sharedEngine = new SharedEngine(SHARED_MAX_CONNECTIONS);
        }
        return sharedEngine;
    }

    /**
     * Connects this client to the given host, runs the full login handshake, and waits until the
     * server considers this connection fully connected.
     */
    public void connect(String host, int serverPort, String serverPassword, Duration timeout)
            throws Exception {
        SharedEngine engine = ensureSharedEngine();

        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<ZomboidNetData> queue =
                (ConcurrentLinkedQueue<ZomboidNetData>) getMainLoopQueue();

        CountDownLatch gotConnection = new CountDownLatch(1);
        engine.registerPendingConnect(
                conn -> {
                    this.connection = conn;
                    sendLogin(conn, username, password, authType);
                    sendLoginQueueRequest(conn);
                    gotConnection.countDown();
                });

        System.out.println(
                "[client:" + username + "] calling Connect to " + host + ":" + serverPort);
        engine.Connect(host, serverPort, serverPassword, false);

        Assertions.assertTrue(
                gotConnection.await(timeout.toMillis(), TimeUnit.MILLISECONDS),
                "[client:" + username + "] RakNet handshake did not complete within " + timeout);

        driveLoginHandshake(queue, timeout);
    }

    /**
     * Processes server response packets and sends the required follow-up packets to complete the
     * login flow. Follows the FakeClientManager pattern: QueueResponse → LoginQueueDone →
     * PlayerConnect → ConnectedPlayer.
     *
     * <p>LoadPlayerProfile is skipped because its responses are processed directly by {@code
     * GameClient.addIncoming} (never queued in MainLoopNetDataQ).
     */
    private void driveLoginHandshake(ConcurrentLinkedQueue<ZomboidNetData> queue, Duration timeout)
            throws Exception {
        Instant deadline = Instant.now().plus(timeout);

        boolean sentQueueDone = false;
        boolean sentPlayerConnect = false;

        while (Instant.now().isBefore(deadline) && !fullyConnected) {
            ZomboidNetData data = queue.poll();
            if (data == null) {
                Thread.sleep(50);
                continue;
            }

            PacketTypes.PacketType ptype = data.type;
            String name = ptype != null ? ptype.name() : "null";

            if (ptype == PacketTypes.PacketType.LoginQueueRequest) {
                byte messageType = data.buffer.getByte();
                System.out.println(
                        "[client:"
                                + username
                                + "] received QueuePacket messageType="
                                + messageType);
                if (!sentQueueDone) {
                    sendLoginQueueDone(connection);
                    sentQueueDone = true;
                    Thread.sleep(100);
                    sendPlayerConnect(connection);
                    sentPlayerConnect = true;
                }

            } else if (ptype == PacketTypes.PacketType.ConnectedPlayer) {
                short id = data.buffer.getShort();
                System.out.println("[client:" + username + "] received ConnectedPlayer id=" + id);
                if (id == -1) {
                    fullyConnected = true;
                }

            } else if (ptype == PacketTypes.PacketType.AccessDenied) {
                Assertions.fail("[client:" + username + "] Server denied access");

            } else {
                System.out.println("[client:" + username + "] received " + name + " (ignored)");
            }
        }

        Assertions.assertTrue(
                fullyConnected,
                "[client:"
                        + username
                        + "] login handshake did not complete within "
                        + timeout
                        + " — sentQueueDone="
                        + sentQueueDone
                        + " sentPlayerConnect="
                        + sentPlayerConnect);

        System.out.println("[client:" + username + "] fully connected");
    }

    public void sendRawNetTimedActionBytes(byte actionByteId, long duration) {
        ByteBufferWriter b = connection.startPacket();
        PacketTypes.PacketType.NetTimedAction.doPacket(b);
        b.putByte(actionByteId);
        b.putEnum(zombie.core.Transaction.TransactionState.Accept);
        b.putLong(duration);
        PacketTypes.PacketType.NetTimedAction.send(connection);
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.forceDisconnect("test-teardown");
            } catch (Throwable ignored) {
            }
            connection = null;
        }
    }

    // ---- Packet senders ----

    private static void sendLogin(
            UdpConnection connection, String username, String password, int authType) {
        ByteBufferWriter b = connection.startPacket();
        PacketTypes.PacketType.Login.doPacket(b);
        b.putUTF(username);
        b.putUTF(password);
        b.putUTF(Core.getInstance().getVersionNumber());
        b.putInt(authType);
        PacketTypes.PacketType.Login.send(connection);
    }

    private static void sendLoginQueueRequest(UdpConnection connection) {
        ByteBufferWriter b = connection.startPacket();
        PacketTypes.PacketType.LoginQueueRequest.doPacket(b);
        PacketTypes.PacketType.LoginQueueRequest.send(connection);
    }

    private static void sendLoginQueueDone(UdpConnection connection) {
        ByteBufferWriter b = connection.startPacket();
        PacketTypes.PacketType.LoginQueueDone.doPacket(b);
        b.putLong(100L);
        PacketTypes.PacketType.LoginQueueDone.send(connection);
    }

    private static void sendPlayerConnect(UdpConnection connection) {
        ByteBufferWriter b = connection.startPacket();
        PacketTypes.PacketType.PlayerConnect.doPacket(b);
        b.putByte(0);
        b.putByte(13);
        b.putByte(0);
        PacketTypes.PacketType.PlayerConnect.send(connection);
    }

    @SuppressWarnings("unchecked")
    private static Queue<?> getMainLoopQueue() throws Exception {
        Field f = GameClient.class.getDeclaredField("MainLoopNetDataQ");
        f.setAccessible(true);
        return (Queue<Object>) f.get(null);
    }

    /**
     * Shared UdpEngine for the whole test JVM. Matches new-on-connect {@link UdpConnection}s
     * against a single pending callback registered by whichever {@link LiveServerClient} called
     * {@code Connect} most recently.
     */
    private static final class SharedEngine extends UdpEngine {

        private final Object pendingLock = new Object();
        private final Set<Long> knownGuids = new HashSet<>();
        private java.util.function.Consumer<UdpConnection> onNextConnected;

        SharedEngine(int maxConnections) throws Exception {
            super(pickUnusedPort(), 0, maxConnections, null, false);
        }

        @SuppressWarnings("unchecked")
        private Map<Long, UdpConnection> connectionMap() {
            try {
                Field f = UdpEngine.class.getDeclaredField("connectionMap");
                f.setAccessible(true);
                return (Map<Long, UdpConnection>) f.get(this);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        void registerPendingConnect(java.util.function.Consumer<UdpConnection> handler) {
            synchronized (pendingLock) {
                if (onNextConnected != null) {
                    throw new IllegalStateException(
                            "another LiveServerClient is already mid-connect");
                }
                onNextConnected = handler;
            }
        }

        @Override
        public void connected() {
            UdpConnection fresh;
            java.util.function.Consumer<UdpConnection> handler;
            synchronized (pendingLock) {
                fresh = findFreshConnection();
                handler = onNextConnected;
                onNextConnected = null;
                if (fresh != null) {
                    knownGuids.add(fresh.getConnectedGUID());
                }
            }
            if (handler != null && fresh != null) {
                handler.accept(fresh);
            }
        }

        private UdpConnection findFreshConnection() {
            Map<Long, UdpConnection> map = connectionMap();
            synchronized (map) {
                for (Map.Entry<Long, UdpConnection> e : new java.util.HashMap<>(map).entrySet()) {
                    if (!knownGuids.contains(e.getKey())) {
                        return e.getValue();
                    }
                }
            }
            return null;
        }

        private static int pickUnusedPort() {
            return 34500;
        }
    }
}
