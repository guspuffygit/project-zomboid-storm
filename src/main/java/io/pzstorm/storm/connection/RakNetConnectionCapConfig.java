package io.pzstorm.storm.connection;

/**
 * Resolves the RakNet incoming-connection cap the dedicated server's listening {@code UdpEngine} is
 * built with, replacing the vanilla hard-coded {@value #VANILLA_CAP}.
 *
 * <p>{@code GameServer.startServer()} passes a bare literal {@code 101} to {@code new
 * UdpEngine(defaultPort, udpPort, 101, serverPassword, true)} — it never consults {@code
 * MaxPlayers}. Since {@code ServerOptions.getMaxPlayers()} is itself capped at {@code 100}, a
 * 100-player server has <b>one</b> spare RakNet slot for its entire login pipeline: every half-open
 * connection, every client still downloading mods, every stalled connect attempt shares that single
 * slot. Once the peer is full RakNet answers new joiners with {@code
 * ID_NO_FREE_INCOMING_CONNECTIONS}, which the vanilla client never handles — no {@code
 * OnConnectFailed}, no state change, so the connect UI sits on "Getting Server Info..." forever.
 *
 * <p><b>Extra RakNet slots cannot admit extra players.</b> The player-count gates are enforced
 * independently of this cap:
 *
 * <ul>
 *   <li>{@code LoginPacket} rejects with {@code AccessDenied "ServerFull"} once {@code
 *       LoginQueue.getCountPlayers() >= ServerOptions.getMaxPlayers()}.
 *   <li>{@code GameServer.getPlayerCount()} counts assigned {@code UdpConnection.playerIds}
 *       entries, not sockets, so an accepted-but-not-logged-in connection is not a player.
 * </ul>
 *
 * So the headroom only changes the <em>failure mode</em>: a would-be joiner over MaxPlayers now
 * gets a handled "server is full" / login-queue position instead of a silent RakNet-level refusal.
 *
 * <p><b>Ceiling — {@value #MAX_CAP}, and it is not negotiable.</b> {@code UdpEngine.decode()} reads
 * the per-connection index straight off the wire as {@code int id = buf.getByte() & 255} and uses
 * it to index {@code connectionArray}, which is a fixed {@code new UdpConnection[256]}. A 257th
 * concurrent connection would wrap to an index already owned by another client and silently
 * overwrite it in {@code addConnection}. {@code GameServer.SlotToConnection} is a roomier {@code
 * new UdpConnection[512]}, but the byte-wide index is the binding constraint, so {@link #MAX_CAP}
 * is a hard clamp on everything below.
 *
 * <p>Overrides (JVM properties, read once per {@link #resolveCap(int, int)} call so a restart is
 * enough):
 *
 * <ul>
 *   <li>{@code -Dstorm.raknet.connectionHeadroom=<n>} — slots above {@code MaxPlayers}. Default
 *       {@value #DEFAULT_HEADROOM}.
 *   <li>{@code -Dstorm.raknet.connectionCap=<n>} — absolute cap, bypasses the headroom calculation.
 * </ul>
 */
public final class RakNetConnectionCapConfig {

    /** The literal {@code GameServer.startServer()} hard-codes. */
    public static final int VANILLA_CAP = 101;

    /** {@code UdpEngine.connectionArray.length}, and the range of the byte-wide wire index. */
    public static final int MAX_CAP = 256;

    /** Spare slots above {@code MaxPlayers} for the login pipeline. */
    public static final int DEFAULT_HEADROOM = 64;

    public static final String HEADROOM_PROPERTY = "storm.raknet.connectionHeadroom";

    public static final String ABSOLUTE_CAP_PROPERTY = "storm.raknet.connectionCap";

    private RakNetConnectionCapConfig() {}

    /**
     * Computes the cap to use in place of {@code vanillaCap}.
     *
     * <p>Never returns less than {@code vanillaCap}: deriving purely from {@code MaxPlayers} would
     * <em>reduce</em> headroom on small servers (the default {@code MaxPlayers=32} plus {@value
     * #DEFAULT_HEADROOM} is 96, below today's {@value #VANILLA_CAP}), so the vanilla value is a
     * floor. Result is clamped to {@link #MAX_CAP}.
     *
     * @param vanillaCap the value the game passed at the patched call site
     * @param maxPlayers {@code ServerOptions.getMaxPlayers()}
     */
    public static int resolveCap(int vanillaCap, int maxPlayers) {
        int floor = Math.max(1, vanillaCap);
        int requested = readAbsoluteCap();
        if (requested <= 0) {
            requested = maxPlayers + readHeadroom();
        }
        if (requested < floor) {
            requested = floor;
        }
        if (requested > MAX_CAP) {
            requested = MAX_CAP;
        }
        return requested;
    }

    private static int readHeadroom() {
        int headroom = readIntProperty(HEADROOM_PROPERTY, DEFAULT_HEADROOM);
        return headroom < 0 ? 0 : headroom;
    }

    private static int readAbsoluteCap() {
        return readIntProperty(ABSOLUTE_CAP_PROPERTY, 0);
    }

    private static int readIntProperty(String key, int fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
