package io.pzstorm.storm.connection;

import zombie.core.raknet.UdpConnection;
import zombie.network.LoginQueue;

/**
 * Classifies a server-side {@link UdpConnection} into exactly one stage of the login pipeline,
 * spanning "RakNet accepted the socket" through to "the character is in the world".
 *
 * <p>Vanilla exposes no such breakdown. {@code GameServer.getPlayerCount()} counts spawned players
 * and PZ's own {@code network} statistics sum everything into per-parameter aggregates, so the
 * entire pre-spawn pipeline — the population that actually exhausts the RakNet peer — is invisible.
 * Every stage below holds one of the {@code UdpEngine.connectionArray} slots capped by {@link
 * RakNetConnectionCapConfig}, whether or not it is a player yet.
 *
 * <p>Stages are mutually exclusive and evaluated in the order of {@link #ALL}, so the per-stage
 * counts always sum to {@code GameServer.udpEngine.connections.size()}. Precedence matters in two
 * places: {@link #GOOGLE_AUTH} is tested before {@link #HANDSHAKE} because a connection pending
 * second-factor auth has no username yet ({@code LoginPacket} only calls {@code setUserName} once
 * {@code needSecondFactor} is false), and the login-queue stages are tested before the checksum
 * stages because a queued connection has already logged in and its checksum state is not what an
 * operator cares about while it waits.
 *
 * <p>The state read here is all main-thread state ({@code LoginQueue}'s monitor plus plain fields
 * on the connection), so classification runs from the server tick and from {@code
 * StalledConnectionReaper.sweep()} — never off the main thread.
 */
public final class ConnectionStage {

    /**
     * RakNet accepted the socket but no {@code Login} packet has arrived — the connection has no
     * username. This is what a client wedged on "Getting Server Info..." looks like from the
     * server, and the only stage vanilla's own reap in {@code GameServer.main} can free.
     */
    public static final String HANDSHAKE = "handshake";

    /**
     * Logged in, waiting on Google second-factor auth. Exempt from the stalled-connection reap
     * until {@code UdpConnection.isGoogleAuthTimeout()} flips.
     */
    public static final String GOOGLE_AUTH = "google_auth";

    /**
     * Second-factor auth was never completed within the vanilla 60s window. Vanilla never frees
     * these, so they accumulate until the stalled-connection reap takes them.
     */
    public static final String GOOGLE_AUTH_TIMEOUT = "google_auth_timeout";

    /** Co-op slave connection waiting for the host to approve it. Exempt from the reap. */
    public static final String COOP_APPROVE = "coop_approve";

    /**
     * Sitting in {@code LoginQueue} behind other joiners. Exempt from the reap — queue wait is the
     * server's own doing.
     */
    public static final String QUEUED = "queued";

    /**
     * The login queue's current occupant: told to proceed, not yet spawned. Bounded by vanilla's
     * {@code loginQueueConnectTimeout}, which only clears the queue slot — it does not disconnect,
     * so a client that dies here falls through to {@link #AWAITING_SPAWN} and leaks its RakNet
     * slot.
     */
    public static final String LOADING = "loading";

    /** Logged in, Lua/script/anim checksum not yet verified ({@code ChecksumState.Init}). */
    public static final String CHECKSUM = "checksum";

    /**
     * Client failed the checksum comparison ({@code ChecksumState.Different}) — a mod mismatch, or
     * a client that tampered with its scripts. {@code AntiCheatChecksumUpdate} acts on these.
     */
    public static final String CHECKSUM_MISMATCH = "checksum_mismatch";

    /**
     * Fully authenticated and past the queue, but the character has not spawned. Covers the client
     * downloading chunks, sitting on the character screen, and "Click Start" campers — {@code
     * setFullyConnected()} only fires in {@code receivePlayerConnect}, so a player parked here
     * holds a slot indefinitely while still answering pings.
     */
    public static final String AWAITING_SPAWN = "awaiting_spawn";

    /** Character is in the world. The only stage that is a player as far as MaxPlayers cares. */
    public static final String FULLY_CONNECTED = "fully_connected";

    /** Every stage in pipeline order, which is also {@link #classify}'s precedence order. */
    public static final String[] ALL = {
        HANDSHAKE,
        GOOGLE_AUTH,
        GOOGLE_AUTH_TIMEOUT,
        COOP_APPROVE,
        QUEUED,
        LOADING,
        CHECKSUM,
        CHECKSUM_MISMATCH,
        AWAITING_SPAWN,
        FULLY_CONNECTED,
    };

    private ConnectionStage() {}

    /** The stage {@code connection} currently occupies. Never null. */
    public static String classify(UdpConnection connection) {
        if (connection.isFullyConnected()) {
            return FULLY_CONNECTED;
        }
        if (connection.awaitingCoopApprove) {
            return COOP_APPROVE;
        }
        if (connection.googleAuth) {
            return connection.isGoogleAuthTimeout() ? GOOGLE_AUTH_TIMEOUT : GOOGLE_AUTH;
        }
        if (connection.getUserName() == null) {
            return HANDSHAKE;
        }
        if (LoginQueue.isInTheQueue(connection)) {
            // wasInLoadingQueue is set exactly when the connection becomes currentLoginQueue, which
            // is how the queue's active occupant is told apart from those still waiting behind it.
            return connection.wasInLoadingQueue() ? LOADING : QUEUED;
        }
        if (connection.checksumState == UdpConnection.ChecksumState.Different) {
            return CHECKSUM_MISMATCH;
        }
        if (connection.checksumState == UdpConnection.ChecksumState.Init) {
            return CHECKSUM;
        }
        return AWAITING_SPAWN;
    }

    /** Index of {@code stage} in {@link #ALL}, or {@code -1} if it is not a known stage. */
    public static int indexOf(String stage) {
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].equals(stage)) {
                return i;
            }
        }
        return -1;
    }
}
