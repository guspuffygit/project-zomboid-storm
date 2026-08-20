package io.pzstorm.storm.connection;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side per-target-id backoff for {@code PlayerDataRequest} sends, wired into the static
 * client send path {@code INetworkPacket.send(PacketType, Object...)} by {@code
 * PlayerDataRequestBackoffPatch}.
 *
 * <p>Vanilla has no negative ack for a refused {@code PlayerDataRequest}: the server silently drops
 * requests for players that are invisible or not relevant to the requester ({@code
 * PlayerDataRequestPacket.processServer}), while the client re-fires the request on <em>every</em>
 * received {@code PlayerPacket} carrying an unknown online id ({@code PlayerPacket.processClient},
 * {@code VehiclePassengers.parsePlayer}). Any relay of updates for a player the receiver is not
 * allowed to resolve therefore produces an infinite request/refusal loop — measured at 5,800
 * packets/s inbound (96% of all inbound) on a production server at 130 players, driven by the
 * role-position relay bypass in {@code PlayerPacket.processServer}.
 *
 * <p>The backoff lets the first request for an online id through immediately — the legitimate
 * "player became relevant" flow is unaffected — and suppresses repeats for the same id for {@code
 * storm.playerDataRequestCooldownMillis} (default 5000, {@code <= 0} disables). Ids are retried
 * after the cooldown, so a request lost to a refusal window still resolves as soon as the target
 * becomes relevant.
 *
 * <p>Fail-open: any unexpected argument shape or internal error allows the send.
 */
public final class StormPlayerDataRequestBackoff {

    static volatile long cooldownNanos =
            Long.getLong("storm.playerDataRequestCooldownMillis", 5000L) * 1_000_000L;

    /**
     * Online ids are shorts handed out per session; the map can only grow past this if id churn is
     * pathological, in which case dropping all history just re-allows one request per live id.
     */
    private static final int MAX_TRACKED_IDS = 2048;

    private static final ConcurrentHashMap<Short, Long> LAST_SENT = new ConcurrentHashMap<>();

    public static volatile long allowedCount;
    public static volatile long suppressedCount;

    private StormPlayerDataRequestBackoff() {}

    /**
     * Decides whether a {@code PlayerDataRequest} send should be suppressed.
     *
     * @param values the varargs passed to {@code INetworkPacket.send}; vanilla call sites pass
     *     exactly one boxed {@code Short} online id — anything else falls through to vanilla
     * @return {@code true} to skip the send (the same id was requested within the cooldown)
     */
    public static boolean shouldSuppress(Object[] values) {
        try {
            long cooldown = cooldownNanos;
            if (cooldown <= 0 || values == null || values.length != 1) {
                return false;
            }
            if (!(values[0] instanceof Short)) {
                return false;
            }
            Short id = (Short) values[0];
            long now = System.nanoTime();
            Long last = LAST_SENT.get(id);
            if (last != null && now - last < cooldown) {
                suppressedCount++;
                return true;
            }
            if (LAST_SENT.size() >= MAX_TRACKED_IDS) {
                LAST_SENT.clear();
            }
            LAST_SENT.put(id, now);
            allowedCount++;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    static void reset() {
        LAST_SENT.clear();
        allowedCount = 0;
        suppressedCount = 0;
    }
}
