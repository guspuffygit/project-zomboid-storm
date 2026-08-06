package io.pzstorm.storm.query;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import zombie.characters.Role;
import zombie.core.Core;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.RakNetPeerInterface;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.random.RandStandard;
import zombie.core.znet.SteamUtils;
import zombie.network.GameClient;
import zombie.network.PacketTypes;
import zombie.network.ZomboidNetData;
import zombie.network.packets.RequestDataPacket;
import zombie.network.packets.connection.LoginPacket;

/**
 * Reads a <i>vanilla</i> server's required workshop items and mods before the game starts, by
 * performing a real login with PZ's own networking stack and stopping as soon as the server's
 * connection details arrive.
 *
 * <p>Vanilla exposes no pre-connect channel for this: A2S truncates its {@code mods} field at 128
 * characters and reports mod ids rather than workshop ids, and {@link StormQueryClient} only works
 * against servers already running Storm. The one place a stock server states its full requirement
 * set is {@code ConnectionDetails}, which {@code GameServer.receiveClientConnect} sends only after
 * {@code LoginPacket} has passed every auth gate. So this probe logs in for real — same
 * credentials, same packets, same slot — reads the payload, and disconnects immediately.
 *
 * <p>Everything on the send side is delegated to the game's own classes ({@link UdpEngine} for the
 * RakNet handshake and BCrypt server-password hashing, {@link LoginPacket#write} for the login
 * body), so the probe cannot drift from what the server speaks. The receive side is hand-parsed
 * instead: vanilla's own reader ends in {@code ConnectionDetails.parse}, which builds a {@code
 * ConnectToServerState} and drives Steam Workshop queries and UI screens that cannot exist here.
 * Only the prefix up to the mod list is decoded; the rest of the payload is ignored.
 *
 * <p>Runs as a child process of the Storm Launcher — which may not reference PZ classes — on the
 * game's own classpath, in Steam mode, with the result on stdout.
 *
 * <p>Usage: {@code ServerModListProbe <host> <port> <username> [timeoutMillis]}, with {@code
 * accountPassword=} and {@code serverPassword=} fed on stdin so neither appears in the process
 * table.
 */
public final class ServerModListProbe {

    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_FAILED = 3;
    public static final int EXIT_NO_REPLY = 4;
    public static final int EXIT_DENIED = 5;
    public static final int EXIT_STEAM_UNAVAILABLE = 6;

    public static final String OK_MARKER = "STORM_MODLIST_OK";

    private static final long DEFAULT_TIMEOUT_MILLIS = 25_000L;

    /** {@code RequestDataManager.packSize} — the server stalls until this much has been ACKed. */
    private static final int ACK_INTERVAL_BYTES = 204_800;

    /** {@code RequestDataPacket.RequestType} ordinals; the enum is package-private. */
    private static final int TYPE_FULL_DATA = 2;

    private static final int TYPE_PART_DATA = 3;
    private static final int TYPE_PART_DATA_ACK = 4;

    /** {@code RequestDataManager.maxLargeFileSize}. */
    private static final int MAX_BLOB_BYTES = 52_428_800;

    private static final int MAX_MODS = 8_192;
    private static final int MAX_WORKSHOP_ITEMS = 8_192;

    private ServerModListProbe() {
        throw new AssertionError("Utility class");
    }

    public static void main(String[] args) {
        int exit;
        try {
            exit = run(args);
        } catch (Denied denied) {
            System.err.println("server refused the probe login: " + denied.getMessage());
            exit = EXIT_DENIED;
        } catch (Throwable t) {
            System.err.println("mod list probe failed: " + t);
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

    private static int run(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println(
                    "usage: ServerModListProbe <host> <port> <username> [timeoutMillis]"
                            + " (accountPassword=/serverPassword= on stdin)");
            return EXIT_USAGE;
        }
        String host = args[0];
        int port = Integer.parseInt(args[1].trim());
        String username = args[2];
        long timeout = args.length > 3 ? Long.parseLong(args[3].trim()) : DEFAULT_TIMEOUT_MILLIS;

        Properties credentials = readCredentials();
        String accountPassword = credentials.getProperty("accountPassword", "");
        String serverPassword = credentials.getProperty("serverPassword", "");

        verifyRequestTypeOrdinals();

        // UdpEngine.Connect answers an unresolvable host by routing through Translator and
        // LuaEventManager, neither of which is loaded here.
        String ip = java.net.InetAddress.getByName(host).getHostAddress();

        if (!initNatives()) {
            System.err.println(
                    "Steam is not available to the probe (is the Steam client running and logged"
                            + " in?)");
            return EXIT_STEAM_UNAVAILABLE;
        }

        ProbeEngine engine = new ProbeEngine();
        try {
            Details details =
                    engine.probe(ip, port, username, accountPassword, serverPassword, timeout);
            if (details == null) {
                return EXIT_NO_REPLY;
            }
            print(details);
            return EXIT_OK;
        } finally {
            // Halting with Steam's socket thread still up makes it assert on stderr; both
            // shutdowns are best-effort, since a native failure must not mask the result.
            try {
                engine.Shutdown();
            } catch (Throwable ignored) {
                // nothing to salvage: the process is about to halt anyway
            }
            try {
                SteamUtils.shutdown();
            } catch (Throwable ignored) {
                // same
            }
        }
    }

    private static Properties readCredentials() throws IOException {
        Properties props = new Properties();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            props.load(reader);
        }
        return props;
    }

    /**
     * A silent reordering of the vanilla enum would turn part-data frames into something else
     * entirely, so the ordinals this class hard-codes are checked against the game before use.
     */
    private static void verifyRequestTypeOrdinals() throws ClassNotFoundException {
        Class<?> type = Class.forName("zombie.network.packets.RequestDataPacket$RequestType");
        Object[] constants = type.getEnumConstants();
        expectConstant(constants, TYPE_FULL_DATA, "FullData");
        expectConstant(constants, TYPE_PART_DATA, "PartData");
        expectConstant(constants, TYPE_PART_DATA_ACK, "PartDataACK");
    }

    private static void expectConstant(Object[] constants, int ordinal, String expected) {
        String actual =
                ordinal < constants.length ? ((Enum<?>) constants[ordinal]).name() : "<absent>";
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "RequestType."
                            + expected
                            + " is no longer ordinal "
                            + ordinal
                            + " (found "
                            + actual
                            + ") — the probe needs updating for this game version");
        }
    }

    /**
     * Steam mode is mandatory here: a Steam-mode server keeps its default port bound to the Steam
     * socket, and its auth reads the caller's Steam id straight off the native layer.
     */
    private static boolean initNatives() {
        if (!"1".equals(System.getProperty("zomboid.steam"))) {
            System.setProperty("zomboid.steam", "1");
        }
        SteamUtils.init();
        if (!SteamUtils.isSteamModeEnabled()) {
            return false;
        }
        RandStandard.INSTANCE.init();

        // RakNet's state-change callback fires Lua events when it runs on the thread that called
        // init(). There is no Lua VM here, so init() runs on a throwaway thread that no callback
        // can ever match; the off-main branch just debug-logs and still reports "Connected".
        Thread rakNetInit = new Thread(RakNetPeerInterface::init, "raknet-init");
        rakNetInit.start();
        try {
            rakNetInit.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        GameClient.client = true;
        GameClient.askPing = false;
        GameClient.askCustomizationData = false;
        GameClient.sendQR = false;

        // LoginPacket.write reads the version off Core; failing here beats failing while the
        // connection's write lock is held.
        System.err.println("probing as game version " + Core.getInstance().getVersionNumber());
        return true;
    }

    private static void print(Details details) {
        StringBuilder out = new StringBuilder();
        out.append(OK_MARKER).append('\n');
        out.append("serverName=").append(sanitize(details.serverName)).append('\n');
        out.append("gameMap=").append(sanitize(details.gameMap)).append('\n');
        out.append("maxPlayers=").append(details.maxPlayers).append('\n');
        for (String item : details.workshopItems) {
            out.append("workshop=").append(sanitize(item)).append('\n');
        }
        for (String mod : details.mods) {
            out.append("mod=").append(sanitize(mod)).append('\n');
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

    /** Thrown for a definitive server-side refusal, which no retry or fallback port can fix. */
    private static final class Denied extends Exception {
        Denied(String reason) {
            super(reason);
        }
    }

    static final class Details {
        String serverName = "";
        String gameMap = "";
        int maxPlayers;
        final Set<String> workshopItems = new LinkedHashSet<>();
        final List<String> mods = new ArrayList<>();
    }

    /**
     * Client-mode {@link UdpEngine} that keeps vanilla's {@code connected()} side effects — a voice
     * channel request and an immediate {@code Login} — out of the probe, so login happens from the
     * probe's own thread once the connection has actually been published.
     */
    private static final class ProbeEngine extends UdpEngine {

        private final CountDownLatch connected = new CountDownLatch(1);

        ProbeEngine() throws Exception {
            // For a non-listening engine RakNet picks its own client port; the first two arguments
            // are ignored.
            super(0, 0, 4, null, false);
        }

        @Override
        public void connected() {
            connected.countDown();
        }

        Details probe(
                String host,
                int port,
                String username,
                String accountPassword,
                String serverPassword,
                long timeoutMillis)
                throws Exception {
            Queue<ZomboidNetData> queue = mainLoopQueue();
            queue.clear();
            GameClient.connection = null;

            // In Steam mode the native callback dials connected() through this field rather than
            // through the engine that owns the socket.
            GameClient.instance.udpEngine = this;

            long deadline = System.currentTimeMillis() + timeoutMillis;
            System.err.println("connecting to " + host + ":" + port + " as " + username);
            Connect(host, port, serverPassword, false);

            UdpConnection connection = awaitConnection(deadline);
            if (connection == null) {
                return null;
            }
            try {
                sendLogin(connection, username, accountPassword);
                return awaitDetails(queue, deadline);
            } finally {
                try {
                    connection.forceDisconnect("storm-modlist-done");
                } catch (Throwable ignored) {
                    // best effort: the server also reaps on RakNet timeout
                }
            }
        }

        /**
         * The Steam-mode handshake advances only while Steam callbacks are pumped — the vanilla
         * client pumps once per frame — and readiness arrives as a native callback with no ordering
         * against the decode thread publishing the connection, so this polls both under a
         * continuous pump.
         */
        private UdpConnection awaitConnection(long deadline) throws InterruptedException {
            while (System.currentTimeMillis() < deadline) {
                if (connected.getCount() == 0) {
                    UdpConnection connection = GameClient.connection;
                    if (connection != null) {
                        return connection;
                    }
                }
                pump(25L);
            }
            System.err.println(
                    connected.getCount() == 0
                            ? "connected but no UdpConnection was published"
                            : "RakNet handshake timed out");
            return null;
        }

        private void sendLogin(UdpConnection connection, String username, String accountPassword) {
            GameClient.username = username;
            // The launcher hands the password over in the game's stored form (BCrypt-of-md5,
            // io.pzstorm.launcher.PzPasswordHash) — already exactly what the server compares
            // against; hashing again here would double-hash it into a guaranteed auth failure.
            GameClient.password = accountPassword;
            GameClient.authType = 1;
            GameClient.startAuth = java.util.Calendar.getInstance();

            LoginPacket login = new LoginPacket();
            ByteBufferWriter writer = connection.startPacket();
            boolean sent = false;
            try {
                PacketTypes.PacketType.Login.doPacket(writer);
                login.write(writer);
                PacketTypes.PacketType.Login.send(connection);
                sent = true;
            } finally {
                if (!sent) {
                    connection.cancelPacket();
                }
            }
        }

        private Details awaitDetails(Queue<ZomboidNetData> queue, long deadline) throws Exception {
            Assembly assembly = new Assembly();
            while (System.currentTimeMillis() < deadline) {
                ZomboidNetData data = queue.poll();
                if (data == null) {
                    pump(20L);
                    continue;
                }
                byte[] blob = consume(data, assembly);
                if (blob != null) {
                    System.err.println("received connection details (" + blob.length + " bytes)");
                    return parseDetails(blob);
                }
            }
            System.err.println(
                    "logged in but the server sent no connection details before the timeout");
            return null;
        }

        /** Steam's callbacks drive the Steam-mode socket, so they must keep being serviced. */
        private void pump(long sleepMillis) throws InterruptedException {
            SteamUtils.runLoop();
            Thread.sleep(sleepMillis);
        }

        /** Returns the assembled connection-details payload once complete, otherwise null. */
        private byte[] consume(ZomboidNetData data, Assembly assembly) throws Denied {
            if (data.type == PacketTypes.PacketType.AccessDenied) {
                throw new Denied(data.buffer.getUTF());
            }
            if (data.type == PacketTypes.PacketType.Kicked) {
                String description = data.buffer.getUTF();
                throw new Denied(description + " / " + data.buffer.getUTF());
            }
            if (data.type != PacketTypes.PacketType.RequestData) {
                return null;
            }
            ByteBufferReader bb = data.buffer;
            int type = bb.getByte() & 0xFF;
            int id = bb.getByte() & 0xFF;
            if (id != RequestDataPacket.RequestID.ConnectionDetails.ordinal()) {
                return null;
            }
            if (type == TYPE_FULL_DATA) {
                byte[] blob = new byte[bb.remaining()];
                bb.get(blob);
                return blob;
            }
            if (type != TYPE_PART_DATA) {
                return null;
            }
            int dataSize = bb.getInt();
            int dataSent = bb.getInt();
            int partSize = bb.getInt();
            byte[] blob = assembly.accept(dataSize, dataSent, partSize, bb);
            // Never ACK a finished transfer: the server drops the request on completion and its
            // ACK handler indexes past the end of an empty request list.
            if (blob == null && assembly.owesAck()) {
                sendAck();
            }
            return blob;
        }

        private void sendAck() {
            UdpConnection connection = GameClient.connection;
            if (connection == null) {
                return;
            }
            ByteBufferWriter writer = connection.startPacket();
            boolean sent = false;
            try {
                PacketTypes.PacketType.RequestData.doPacket(writer);
                writer.putByte((byte) TYPE_PART_DATA_ACK);
                writer.putByte((byte) RequestDataPacket.RequestID.ConnectionDetails.ordinal());
                PacketTypes.PacketType.RequestData.send(connection);
                sent = true;
            } finally {
                if (!sent) {
                    connection.cancelPacket();
                }
            }
        }

        @SuppressWarnings("unchecked")
        private static Queue<ZomboidNetData> mainLoopQueue() throws Exception {
            Field field = GameClient.class.getDeclaredField("MainLoopNetDataQ");
            field.setAccessible(true);
            return (Queue<ZomboidNetData>) field.get(null);
        }
    }

    /** Reassembles the chunked payload and tracks when the server is owed an ACK. */
    static final class Assembly {

        private byte[] blob;
        private int received;
        private int sinceAck;
        private boolean owesAck;

        byte[] accept(int dataSize, int dataSent, int partSize, ByteBufferReader bb) {
            if (dataSize <= 0 || dataSize > MAX_BLOB_BYTES) {
                throw new IllegalStateException("implausible payload size " + dataSize);
            }
            if (blob == null) {
                blob = new byte[dataSize];
            }
            if (dataSent < 0 || partSize < 0 || dataSent + partSize > blob.length) {
                throw new IllegalStateException(
                        "chunk " + dataSent + "+" + partSize + " outside payload " + blob.length);
            }
            bb.get(blob, dataSent, partSize);
            received += partSize;
            sinceAck += partSize;
            if (sinceAck >= ACK_INTERVAL_BYTES) {
                sinceAck = 0;
                owesAck = true;
            }
            return received >= blob.length ? blob : null;
        }

        boolean owesAck() {
            boolean owed = owesAck;
            owesAck = false;
            return owed;
        }
    }

    /**
     * Decodes the payload prefix written by {@code ConnectionDetails.write}: server details, game
     * map, the Steam-mode workshop item block, then the mod list. Anything past the mod list —
     * start location, server and sandbox options, world dictionary — is left unread.
     *
     * <p>The workshop block is present only when the <i>server</i> runs in Steam mode, and nothing
     * earlier in the payload says whether it did, so a payload that fails to decode with the block
     * is retried without it.
     */
    static Details parseDetails(byte[] blob) {
        RuntimeException failure = null;
        for (boolean withWorkshopItems : new boolean[] {true, false}) {
            try {
                return parseDetails(blob, withWorkshopItems);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }
        throw failure;
    }

    private static Details parseDetails(byte[] blob, boolean withWorkshopItems) {
        ByteBufferReader bb = new ByteBufferReader(ByteBuffer.wrap(blob));
        Details details = new Details();

        bb.getBoolean(); // isCoopHost
        details.maxPlayers = bb.getInt();
        if (bb.getBoolean()) {
            bb.getLong(); // coop host steam id
            details.serverName = bb.getUTF();
        }
        bb.getByte(); // player index
        new Role("storm-probe").parse(bb);
        details.gameMap = bb.getUTF();

        if (withWorkshopItems) {
            int count = bb.getShort();
            if (count < 0 || count > MAX_WORKSHOP_ITEMS) {
                throw new IllegalStateException("implausible workshop item count " + count);
            }
            for (int i = 0; i < count; i++) {
                details.workshopItems.add(Long.toString(bb.getLong()));
                bb.getLong(); // server-side install timestamp
            }
        }

        int modCount = bb.getInt();
        if (modCount < 0 || modCount > MAX_MODS) {
            throw new IllegalStateException("implausible mod count " + modCount);
        }
        for (int i = 0; i < modCount; i++) {
            String modId = bb.getUTF();
            String workshopId = bb.getUTF();
            bb.getUTF(); // display name
            details.mods.add(modId);
            if (isWorkshopId(workshopId)) {
                details.workshopItems.add(workshopId);
            }
        }
        return details;
    }

    /**
     * Mods installed outside the workshop carry an empty or non-numeric id, and a bad id would make
     * the launcher's Steam child fail the whole batch.
     */
    static boolean isWorkshopId(String value) {
        if (value == null || value.isEmpty() || value.length() > 20 || value.equals("0")) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
