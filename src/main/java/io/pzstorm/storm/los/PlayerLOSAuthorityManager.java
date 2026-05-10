package io.pzstorm.storm.los;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;

/**
 * Tracks per-player solo/grouped authority state for the client-Lua LOS rollout.
 *
 * <p>A player is "solo" when no other connected player is close enough that their LOS regions could
 * overlap. Solo players' client-reported LOS can later be trusted authoritatively; grouped players
 * must stay on server-computed LOS to avoid client-time-sync conflicts in shared regions.
 *
 * <p>Hysteresis prevents flapping near the boundary:
 *
 * <ul>
 *   <li>becomes <b>solo</b> when nearest other player is at least {@link #SOLO_THRESHOLD_SQUARES}
 *       squares away (or no other player is online)
 *   <li>becomes <b>grouped</b> when nearest other player is within {@link
 *       #GROUPED_THRESHOLD_SQUARES} squares
 *   <li>between the two thresholds, the previous state sticks
 * </ul>
 *
 * <p>Phase 1 is observability-only: nothing reads {@link #isSolo(short)} yet, no client packets, no
 * LOS routing change. Transitions are logged so the state machine can be validated against real
 * player movement before any behavior is wired up.
 */
public final class PlayerLOSAuthorityManager {

    public static final PlayerLOSAuthorityManager INSTANCE = new PlayerLOSAuthorityManager();

    public static final float SOLO_THRESHOLD_SQUARES = 256f;
    public static final float GROUPED_THRESHOLD_SQUARES = 192f;

    private static final float SOLO_THRESHOLD_SQ = SOLO_THRESHOLD_SQUARES * SOLO_THRESHOLD_SQUARES;
    private static final float GROUPED_THRESHOLD_SQ =
            GROUPED_THRESHOLD_SQUARES * GROUPED_THRESHOLD_SQUARES;

    private final Map<Short, AuthorityState> states = new HashMap<>();
    private final HashSet<Short> presentScratch = new HashSet<>();

    private PlayerLOSAuthorityManager() {}

    public boolean isSolo(short onlineID) {
        AuthorityState s = states.get(onlineID);
        return s != null && s.solo;
    }

    public void tick() {
        if (!GameServer.server) {
            return;
        }
        ArrayList<IsoPlayer> players = GameServer.Players;
        int n = players.size();

        presentScratch.clear();
        for (int i = 0; i < n; i++) {
            IsoPlayer p = players.get(i);
            if (p == null) {
                continue;
            }
            presentScratch.add(p.getOnlineID());
        }

        Iterator<Map.Entry<Short, AuthorityState>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Short, AuthorityState> e = it.next();
            if (!presentScratch.contains(e.getKey())) {
                LOGGER.info(
                        "[LOSAuthority] disconnected onlineID={} (was {})",
                        e.getKey(),
                        e.getValue().solo ? "SOLO" : "GROUPED");
                it.remove();
            }
        }

        for (int i = 0; i < n; i++) {
            IsoPlayer p = players.get(i);
            if (p == null) {
                continue;
            }
            updatePlayerState(p, players);
        }
    }

    private void updatePlayerState(IsoPlayer player, ArrayList<IsoPlayer> all) {
        short id = player.getOnlineID();
        float nearestSq = nearestOtherDistanceSq(player, all);

        AuthorityState state = states.get(id);
        if (state == null) {
            boolean initialSolo = (nearestSq < 0f || nearestSq >= SOLO_THRESHOLD_SQ);
            states.put(id, new AuthorityState(initialSolo));
            logTransition(player, "INITIAL " + (initialSolo ? "SOLO" : "GROUPED"), nearestSq);
            return;
        }

        boolean newSolo = state.solo;
        if (nearestSq < 0f || nearestSq >= SOLO_THRESHOLD_SQ) {
            newSolo = true;
        } else if (nearestSq <= GROUPED_THRESHOLD_SQ) {
            newSolo = false;
        }

        if (newSolo != state.solo) {
            logTransition(player, state.solo ? "SOLO -> GROUPED" : "GROUPED -> SOLO", nearestSq);
            state.solo = newSolo;
        }
    }

    private float nearestOtherDistanceSq(IsoPlayer player, ArrayList<IsoPlayer> all) {
        float bestSq = -1f;
        float px = player.getX();
        float py = player.getY();
        float pz = player.getZ();
        int n = all.size();
        for (int i = 0; i < n; i++) {
            IsoPlayer other = all.get(i);
            if (other == null || other == player) {
                continue;
            }
            float dx = other.getX() - px;
            float dy = other.getY() - py;
            float dz = other.getZ() - pz;
            float sq = dx * dx + dy * dy + dz * dz;
            if (bestSq < 0f || sq < bestSq) {
                bestSq = sq;
            }
        }
        return bestSq;
    }

    private void logTransition(IsoPlayer player, String transition, float nearestSq) {
        String distance = nearestSq < 0f ? "none" : String.format("%.1f", Math.sqrt(nearestSq));
        LOGGER.info(
                "[LOSAuthority] {} (onlineID={}): {} (nearest other = {} squares)",
                player.getUsername(),
                player.getOnlineID(),
                transition,
                distance);
    }

    private static final class AuthorityState {
        boolean solo;

        AuthorityState(boolean solo) {
            this.solo = solo;
        }
    }
}
