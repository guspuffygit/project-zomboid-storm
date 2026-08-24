package io.pzstorm.storm.query;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.RakNetPeerInterface;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.random.RandStandard;
import zombie.core.znet.SteamUtils;
import zombie.network.GameClient;
import zombie.network.ZomboidNetData;

/**
 * Standalone client for {@link StormQueryProtocol}, run as a child process by the Storm Launcher to
 * learn a server's required workshop items before the game starts.
 *
 * <p>It deliberately drives Project Zomboid's <i>own</i> {@link UdpEngine} and RakNet natives
 * rather than reimplementing the protocol. RakNet's connected layer (MTU negotiation, reliability,
 * ordering, split-packet reassembly) and PZ's BCrypt server-password hashing are all non-trivial
 * and can change with the game; borrowing the shipped implementation means this client cannot drift
 * from what the server speaks. That is also why it lives in Storm core rather than in the launcher,
 * which may not reference PZ classes — the launcher spawns this on the game's own classpath and
 * reads the result off stdout.
 *
 * <p>No login is performed. The query is answered from {@code GameServer.addIncoming}, ahead of the
 * server's login gate, so the connection is dropped again within a second or two and never occupies
 * a player slot.
 *
 * <p>Usage: {@code StormQueryClient <host> <port> [serverPassword] [timeoutMillis]}
 */
public final class StormQueryClient {

    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_FAILED = 3;
    public static final int EXIT_NO_REPLY = 4;

    private static final long DEFAULT_TIMEOUT_MILLIS = 15_000L;

    private StormQueryClient() {
        throw new AssertionError("Utility class");
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println(
                    "usage: StormQueryClient <host> <port> [serverPassword] [timeoutMillis]");
            halt(EXIT_USAGE);
        }
        int exit;
        try {
            String host = args[0];
            int port = Integer.parseInt(args[1]);
            String serverPassword = args.length > 2 ? args[2] : "";
            long timeout = args.length > 3 ? Long.parseLong(args[3]) : DEFAULT_TIMEOUT_MILLIS;
            exit = run(host, port, serverPassword, timeout);
        } catch (Throwable t) {
            System.err.println("storm query failed: " + t);
            t.printStackTrace();
            exit = EXIT_FAILED;
        }
        halt(exit);
    }

    /** RakNet's native threads outlive {@code main}; the parent only wants stdout and a status. */
    private static void halt(int status) {
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(status);
    }

    private static int run(String host, int port, String serverPassword, long timeoutMillis)
            throws Exception {
        initNatives();

        // A Steam-mode server holds its defaultPort with the Steam socket and answers raw RakNet on
        // its udpPort (conventionally defaultPort + 1); a -nosteam server answers on defaultPort
        // itself. Trying both costs one timeout at worst and spares the launcher extra config.
        int[] candidates = {port + 1, port};
        long perAttempt = Math.max(3_000L, timeoutMillis / candidates.length);

        // RakNet's native layer supports only one peer per process, so every attempt reuses the
        // same engine.
        QueryEngine engine = new QueryEngine();
        try {
            for (int i = 0; i < candidates.length; i++) {
                int candidate = candidates[i];
                System.err.println("querying " + host + ":" + candidate);
                try {
                    Reply reply = engine.query(host, candidate, serverPassword, perAttempt);
                    if (reply != null) {
                        print(reply);
                        return EXIT_OK;
                    }
                } catch (Throwable t) {
                    System.err.println("port " + candidate + " failed: " + t);
                }
            }
        } finally {
            try {
                engine.Shutdown();
            } catch (Throwable ignored) {
            }
        }
        System.err.println("no Storm query reply from " + host + ":" + port);
        return EXIT_NO_REPLY;
    }

    private static void initNatives() {
        // Steam mode would route the connect through the Steam relay and need a running Steam
        // client; a direct UDP connect works wherever the launcher runs.
        System.clearProperty("zomboid.steam");
        SteamUtils.init();
        RandStandard.INSTANCE.init();
        RakNetPeerInterface.init();
        GameClient.client = true;
    }

    private static void print(Reply reply) {
        StringBuilder out = new StringBuilder();
        out.append("STORM_QUERY_OK\n");
        out.append("stormVersion=").append(sanitize(reply.stormVersion)).append('\n');
        out.append("gameVersion=").append(sanitize(reply.gameVersion)).append('\n');
        out.append("serverName=").append(sanitize(reply.serverName)).append('\n');
        out.append("maxPlayers=").append(reply.maxPlayers).append('\n');
        out.append("players=").append(reply.players).append('\n');
        for (int i = 0; i < reply.workshopItems.size(); i++) {
            out.append("workshop=").append(sanitize(reply.workshopItems.get(i))).append('\n');
        }
        for (int i = 0; i < reply.mods.size(); i++) {
            out.append("mod=").append(sanitize(reply.mods.get(i))).append('\n');
        }
        if (!reply.checksumLua.isEmpty()
                || !reply.checksumScript.isEmpty()
                || !reply.checksumAnim.isEmpty()) {
            out.append("checksumLua=").append(sanitize(reply.checksumLua)).append('\n');
            out.append("checksumScript=").append(sanitize(reply.checksumScript)).append('\n');
            out.append("checksumAnim=").append(sanitize(reply.checksumAnim)).append('\n');
        }
        System.out.print(out);
        System.out.flush();
    }

    /** Server-controlled strings must never break the parent's line-based parsing. */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static final class Reply {
        String stormVersion = "";
        String gameVersion = "";
        String serverName = "";
        int maxPlayers;
        int players;
        final List<String> workshopItems = new ArrayList<>();
        final List<String> mods = new ArrayList<>();
        String checksumLua = "";
        String checksumScript = "";
        String checksumAnim = "";
    }

    /**
     * Client-mode {@link UdpEngine} whose {@code connected()} override replaces vanilla's — which
     * would immediately send a {@code Login} packet and start a real join.
     */
    private static final class QueryEngine extends UdpEngine {

        private volatile CountDownLatch connected = new CountDownLatch(1);

        QueryEngine() throws Exception {
            // For a non-listening engine RakNet picks its own client port; the first two arguments
            // are ignored.
            super(0, 0, 4, null, false);
        }

        @Override
        public void connected() {
            connected.countDown();
        }

        Reply query(String host, int port, String serverPassword, long timeoutMillis)
                throws Exception {
            Queue<ZomboidNetData> queue = mainLoopQueue();
            queue.clear();
            connected = new CountDownLatch(1);
            GameClient.connection = null;

            long deadline = System.currentTimeMillis() + timeoutMillis;
            Connect(host, port, serverPassword, false);
            if (!connected.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                System.err.println("RakNet handshake timed out on port " + port);
                return null;
            }
            UdpConnection connection = GameClient.connection;
            if (connection == null) {
                System.err.println("connected but no UdpConnection was published");
                return null;
            }
            try {
                sendQuery(connection);
                return awaitReply(queue, deadline);
            } finally {
                try {
                    connection.forceDisconnect("storm-query-done");
                } catch (Throwable ignored) {
                }
            }
        }

        private Reply awaitReply(Queue<ZomboidNetData> queue, long deadline)
                throws InterruptedException {
            while (System.currentTimeMillis() < deadline) {
                ZomboidNetData data = queue.poll();
                if (data == null) {
                    Thread.sleep(25L);
                    continue;
                }
                Reply reply = tryParse(data);
                if (reply != null) {
                    return reply;
                }
            }
            System.err.println("connected, but the server sent no Storm query reply");
            return null;
        }

        private void sendQuery(UdpConnection connection) {
            ByteBufferWriter writer = connection.startPacket();
            boolean sent = false;
            try {
                writer.putByte((byte) StormQueryProtocol.RAKNET_USER_PACKET_ENUM);
                writer.putShort(StormQueryProtocol.QUERY_PACKET_ID);
                writer.putInt(StormQueryProtocol.MAGIC);
                writer.putInt(StormQueryProtocol.PROTOCOL_VERSION);
                connection.endPacketImmediate();
                sent = true;
            } finally {
                if (!sent) {
                    connection.cancelPacket();
                }
            }
        }

        /**
         * Our reply id is outside the vanilla enum, so {@code ZomboidNetData.type} is null and the
         * id itself is not retained — the payload magic is what identifies the packet.
         */
        private Reply tryParse(ZomboidNetData data) {
            if (data.type != null) {
                return null;
            }
            ByteBufferReader bb = data.buffer;
            if (bb.bb.remaining() < 8) {
                return null;
            }
            try {
                if (bb.getInt() != StormQueryProtocol.MAGIC) {
                    return null;
                }
                int protocolVersion = bb.getInt();
                Reply reply = new Reply();
                reply.stormVersion = bb.getUTF();
                reply.gameVersion = bb.getUTF();
                reply.serverName = bb.getUTF();
                reply.maxPlayers = bb.getInt();
                reply.players = bb.getInt();
                readStrings(bb, reply.workshopItems);
                readStrings(bb, reply.mods);
                if (protocolVersion >= 2 && bb.bb.remaining() > 0) {
                    // v2 appends the server's join-checksum totals (Lua, scripts, animations)
                    reply.checksumLua = bb.getUTF();
                    reply.checksumScript = bb.getUTF();
                    reply.checksumAnim = bb.getUTF();
                }
                return reply;
            } catch (Throwable t) {
                System.err.println("malformed Storm query reply: " + t);
                return null;
            }
        }

        private static void readStrings(ByteBufferReader bb, List<String> into) {
            int count = bb.getInt();
            if (count < 0 || count > 65_536) {
                throw new IllegalStateException("implausible string count " + count);
            }
            for (int i = 0; i < count; i++) {
                into.add(bb.getUTF());
            }
        }

        @SuppressWarnings("unchecked")
        private static Queue<ZomboidNetData> mainLoopQueue() throws Exception {
            Field field = GameClient.class.getDeclaredField("MainLoopNetDataQ");
            field.setAccessible(true);
            return (Queue<ZomboidNetData>) field.get(null);
        }
    }
}
