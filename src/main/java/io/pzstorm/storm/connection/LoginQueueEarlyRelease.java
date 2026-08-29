package io.pzstorm.storm.connection;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.packet.LoginQueueDonePacketEvent;
import io.pzstorm.storm.event.packet.RequestDataPacketEvent;
import io.pzstorm.storm.metrics.LoginQueueEarlyReleaseMetrics;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongPredicate;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;

/**
 * Frees the login-queue slot as soon as a joiner's last server round-trip completes, instead of
 * holding it through the client-local load — cutting the per-join slot hold from the full {@code
 * GameLoadingState} (~39 s observed) to the server-dependent prefix (~6–8 s).
 *
 * <p>The one knob is {@code -Dstorm.loginQueueMaxConcurrentLoaders=<n>}: the maximum number of
 * joiners allowed to be loading into the server at once (released loaders plus the slot-holder).
 * The default of {@value #DEFAULT_MAX_CONCURRENT_LOADERS} reproduces vanilla admission exactly —
 * one loading joiner, the slot held until {@code LoginQueueDone} — so nothing changes until an
 * admin raises the flag.
 *
 * <h2>Why the WorldMap request is the release point</h2>
 *
 * <p>Vanilla admits exactly one connection at a time ({@code LoginQueue.currentLoginQueue}) and
 * only clears the slot at {@code LoginQueueDone} — sent at the very end of {@code
 * GameLoadingState}, after ~15 s of purely client-local work ({@code IsoWorld.init()}, physics
 * meshes, textures; the initial cell load runs with {@code IsoChunk.doServerRequests = false}). The
 * client's join-time download chain in {@code GameClient.GameLoadingRequestData()} is strictly
 * sequential — ZombieOutfitDescriptors → PlayerZombieDescriptors → RadioData → WorldMap — and
 * WorldMap is terminal: on receiving it the client sets {@code RequestState.Complete} and never
 * writes back. So when the server receives {@code Request(WorldMap)}, the joiner has fully received
 * the three prior payloads, and by the time {@code processServer} returns (this event is dispatched
 * after it) the WorldMap payload itself is already handed to RakNet. Releasing the slot here
 * overlaps only client-local work; at most one joiner at a time is ever in the server-dependent
 * download phase, so admission stays self-regulating.
 *
 * <p>Accounting is unchanged: an admitted connection already has {@code wasInLoadingQueue} set and
 * is out of both queue lists, so {@code LoginQueue.getCountPlayers()} counts it against {@code
 * MaxPlayers} exactly as it counts the vanilla slot-holder. A released loader that stalls is in the
 * same state as one vanilla's own {@code loginQueueConnectTimeout} path leaves behind (that path
 * also clears the slot without disconnecting) and is covered by the stalled-connection reaper.
 *
 * <h2>Main-thread discipline</h2>
 *
 * <p>Both handlers run inside {@code GameServer.mainLoopDealWithNetData} on the server main thread
 * — the identical context in which vanilla's {@code receiveLoginQueueDone} mutates the slot — and
 * take the same {@code LoginQueue} list monitor around the mutation. No other thread ever touches
 * the tracker map.
 *
 * <p>Every step fails soft: reflection breaking on a game update means no early release and the
 * vanilla slot hold runs unchanged.
 */
public final class LoginQueueEarlyRelease {

    /**
     * Maximum joiners loading into the server at once — released loaders plus the current
     * slot-holder. 1 = vanilla admission (the slot is never released early).
     */
    public static final int DEFAULT_MAX_CONCURRENT_LOADERS = 1;

    public static final int MIN_MAX_CONCURRENT_LOADERS = 1;
    public static final int MAX_MAX_CONCURRENT_LOADERS = 32;

    /**
     * Backstop age for tracker entries whose connection stays active but never finishes loading;
     * matches the stalled-connection reaper's default horizon, which disconnects such joiners.
     */
    static final long MAX_LOADER_AGE_MS = 600_000L;

    private static final int MAX_CONCURRENT_LOADERS =
            clampLoaders(
                    Integer.getInteger(
                            "storm.loginQueueMaxConcurrentLoaders",
                            DEFAULT_MAX_CONCURRENT_LOADERS));

    private static final LoaderTracker TRACKER = new LoaderTracker();

    private LoginQueueEarlyRelease() {}

    static int clampLoaders(int value) {
        return Math.max(MIN_MAX_CONCURRENT_LOADERS, Math.min(MAX_MAX_CONCURRENT_LOADERS, value));
    }

    @SubscribeEvent
    public static void onRequestData(RequestDataPacketEvent event) {
        if (!GameServer.server) {
            return;
        }
        try {
            if (!isTerminalWorldMapRequest(event.getField("type"), event.getField("id"))) {
                return;
            }
            tryRelease(event.connection);
        } catch (Throwable t) {
            LOGGER.error(
                    "LoginQueueEarlyRelease: release attempt failed for {} — slot left for the"
                            + " vanilla path to clear",
                    event.username,
                    t);
        }
    }

    @SubscribeEvent
    public static void onLoginQueueDone(LoginQueueDonePacketEvent event) {
        if (!GameServer.server) {
            return;
        }
        try {
            Long releasedAtMs = TRACKER.remove(event.connection.getConnectedGUID());
            if (releasedAtMs != null) {
                LoginQueueEarlyReleaseMetrics.observeReclaimedSeconds(
                        (System.currentTimeMillis() - releasedAtMs) / 1000.0);
            }
            LoginQueueEarlyReleaseMetrics.setConcurrentLoaders(inFlightLoaders());
        } catch (Throwable t) {
            LOGGER.error("LoginQueueEarlyRelease: LoginQueueDone bookkeeping failed", t);
        }
    }

    /**
     * {@code true} only for the client's initial {@code Request(WorldMap)} — the terminal entry of
     * the join-time download chain. Matched by enum name so this class never references the
     * package-private {@code RequestDataPacket.RequestType}; a rename on a game update just means
     * no early release (vanilla slot hold), never a wrong release.
     */
    static boolean isTerminalWorldMapRequest(Object type, Object id) {
        return type instanceof Enum<?> typeEnum
                && "Request".equals(typeEnum.name())
                && id instanceof Enum<?> idEnum
                && "WorldMap".equals(idEnum.name());
    }

    private static void tryRelease(UdpConnection connection) throws Exception {
        if (MAX_CONCURRENT_LOADERS <= 1 || !LoginQueueReflection.init()) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (LoginQueueReflection.queueMonitor()) {
            if (LoginQueueReflection.currentLoginQueue() != connection) {
                // in-game WorldMap re-request, or the slot already moved on (timeout) — no-op
                return;
            }
            int inFlight = inFlightLoadersAt(now);
            // after a release: inFlight + this joiner + the next admitted slot-holder are loading
            if (inFlight + 2 > MAX_CONCURRENT_LOADERS) {
                LoginQueueEarlyReleaseMetrics.capped();
                LoginQueueEarlyReleaseMetrics.setConcurrentLoaders(inFlight);
                LOGGER.debug(
                        "LoginQueueEarlyRelease: {} released loaders in flight (max {} loading) —"
                                + " holding the slot for {}",
                        inFlight,
                        MAX_CONCURRENT_LOADERS,
                        connection.getUserName());
                return;
            }
            LoginQueueReflection.clearCurrentLoginQueue();
            TRACKER.add(connection.getConnectedGUID(), now);
            LoginQueueReflection.loadNextPlayer();
            LoginQueueEarlyReleaseMetrics.released();
            LoginQueueEarlyReleaseMetrics.setConcurrentLoaders(inFlight + 1);
            LOGGER.debug(
                    "LoginQueueEarlyRelease: released the login slot for {} at the WorldMap"
                            + " request ({} released loaders now in flight)",
                    connection.getUserName(),
                    inFlight + 1);
        }
    }

    private static int inFlightLoaders() {
        return inFlightLoadersAt(System.currentTimeMillis());
    }

    private static int inFlightLoadersAt(long nowMs) {
        return TRACKER.countInFlight(
                nowMs, MAX_LOADER_AGE_MS, LoginQueueEarlyRelease::stillLoading);
    }

    /** A tracked joiner still occupies a loader slot only while connected and not yet in-game. */
    private static boolean stillLoading(long connectionGuid) {
        if (GameServer.udpEngine == null) {
            return false;
        }
        UdpConnection connection = GameServer.udpEngine.getActiveConnection(connectionGuid);
        return connection != null && !connection.isFullyConnected();
    }

    // test hook
    static LoaderTracker tracker() {
        return TRACKER;
    }

    /**
     * Released-but-still-loading joiners, keyed by connection GUID with the release timestamp as
     * value. Self-cleaning: {@link #countInFlight} drops entries whose connection is gone or fully
     * in-game (disconnects need no dedicated hook) plus an age backstop. Main-thread only — mirrors
     * {@code LoginQueue}'s own threading — so a plain map suffices.
     */
    static final class LoaderTracker {

        private final Map<Long, Long> releasedAtMsByGuid = new LinkedHashMap<>();

        void add(long connectionGuid, long nowMs) {
            releasedAtMsByGuid.put(connectionGuid, nowMs);
        }

        /**
         * Returns the release timestamp, or {@code null} if the joiner was never released early.
         */
        Long remove(long connectionGuid) {
            return releasedAtMsByGuid.remove(connectionGuid);
        }

        /**
         * Sweeps entries that no longer occupy a loader slot ({@code stillLoading} false, or older
         * than {@code maxAgeMs}) and returns how many remain.
         */
        int countInFlight(long nowMs, long maxAgeMs, LongPredicate stillLoading) {
            for (Iterator<Map.Entry<Long, Long>> it = releasedAtMsByGuid.entrySet().iterator();
                    it.hasNext(); ) {
                Map.Entry<Long, Long> entry = it.next();
                if (nowMs - entry.getValue() > maxAgeMs || !stillLoading.test(entry.getKey())) {
                    it.remove();
                }
            }
            return releasedAtMsByGuid.size();
        }

        int size() {
            return releasedAtMsByGuid.size();
        }

        void clear() {
            releasedAtMsByGuid.clear();
        }
    }
}
