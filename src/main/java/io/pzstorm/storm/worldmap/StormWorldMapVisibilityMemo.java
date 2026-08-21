package io.pzstorm.storm.worldmap;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.WorldMapVisibilityMemoMetrics;
import java.util.HashMap;
import java.util.List;
import zombie.characters.Capability;
import zombie.characters.Faction;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.iso.areas.SafeHouse;
import zombie.network.GameServer;
import zombie.network.ServerOptions;

/**
 * Per-batch memo for {@code GameServer.shouldSendWorldMapPlayerPosition(UdpConnection, IsoPlayer)}.
 *
 * <p>Once a second the server runs that predicate for every (connection, player) pair — O(P²)
 * evaluations. Each evaluation re-derives the same facts from scratch with linear scans: {@code
 * Faction.getPlayerFaction} walks every faction's member list twice per pair (once for each side),
 * and {@code SafeHouse.isInSameSafehouse} walks every safehouse's member list with string compares,
 * for each of the connection's up-to-4 players. On a server with hundreds of safehouses that is ~10
 * µs per pair, ~120 ms of main thread burst every second at 109 players — a visible hitch.
 *
 * <p>{@link #begin()} (called once at the top of the all-connections {@code
 * sendWorldMapPlayerPosition()}) indexes factions and safehouses by username in one pass so that
 * {@link #evaluate} answers each pair from two hash lookups and a tiny array intersection. Nothing
 * the predicate reads can change while the batch runs on the main thread, so the memo is exact:
 * first-matching-faction semantics and {@code playerAllowed} (owner-or-member) semantics are
 * reproduced literally. {@link #end()} drops the tables; outside a batch (the client-initiated
 * {@code receiveWorldMapPlayerPosition} path) every call falls through to vanilla.
 *
 * <p>Always on. Any throwable latches the memo off for the rest of the process and the vanilla
 * predicate runs unchanged.
 */
public final class StormWorldMapVisibilityMemo {

    private static final int NONE = 1;
    private static final int FACTION_ONLY = 2;
    private static final int FACTION_AND_VISIBLE_ONLY = 3;
    private static final int ALL = 4;

    private static final Tables TABLES = new Tables();
    private static final Member[] CONNECTION_MEMBERS = new Member[4];

    private static boolean active;
    private static boolean disabled;
    private static int mode;
    private static UdpConnection currentConnection;
    private static boolean currentSeesWorldMap;

    private StormWorldMapVisibilityMemo() {}

    /** Builds the per-batch tables. Call at the top of the all-connections send. */
    public static void begin() {
        if (disabled) {
            return;
        }
        long start = System.nanoTime();
        try {
            TABLES.clear();
            List<Faction> factions = Faction.getFactions();
            for (int i = 0; i < factions.size(); i++) {
                TABLES.indexFaction(factions.get(i));
            }
            List<SafeHouse> safehouses = SafeHouse.getSafehouseList();
            for (int i = 0; i < safehouses.size(); i++) {
                SafeHouse safehouse = safehouses.get(i);
                TABLES.indexSafehouse(i, safehouse.getOwner(), safehouse.getPlayers());
            }
            mode = ServerOptions.getInstance().mapRemotePlayerVisibility.getValue();
            currentConnection = null;
            active = true;
            WorldMapVisibilityMemoMetrics.recordBuild(System.nanoTime() - start);
        } catch (Throwable t) {
            fail("build", t);
        }
    }

    /** Drops the per-batch tables; every later predicate call runs vanilla until the next batch. */
    public static void end() {
        active = false;
        currentConnection = null;
        TABLES.clear();
        for (int i = 0; i < CONNECTION_MEMBERS.length; i++) {
            CONNECTION_MEMBERS[i] = null;
        }
    }

    /**
     * Memoized {@code shouldSendWorldMapPlayerPosition}. Returns {@code 1} for true, {@code 0} for
     * false, or {@code -1} when the vanilla predicate must run (no batch active or memo disabled).
     */
    public static int evaluate(UdpConnection c, IsoPlayer player) {
        if (!active) {
            WorldMapVisibilityMemoMetrics.vanillaEvaluations++;
            return -1;
        }
        try {
            int result = evaluateActive(c, player);
            if (result < 0) {
                WorldMapVisibilityMemoMetrics.vanillaEvaluations++;
            } else {
                WorldMapVisibilityMemoMetrics.memoEvaluations++;
            }
            return result;
        } catch (Throwable t) {
            fail("evaluate", t);
            WorldMapVisibilityMemoMetrics.vanillaEvaluations++;
            return -1;
        }
    }

    /** Same contract as {@link #evaluate}: 1 true, 0 false, -1 defer to vanilla. */
    private static int evaluateActive(UdpConnection c, IsoPlayer player) {
        if (player == null || player.isDead()) {
            return 0;
        }
        UdpConnection c2 = GameServer.getConnectionFromPlayer(player);
        if (c2 == null || c2 == c || !c2.isFullyConnected()) {
            return 0;
        }
        if (c.getRole() == null) {
            // A connection mid-login has no role until receiveLogin assigns one; vanilla NPEs on
            // c.getRole().hasCapability(...) for it and the main loop's per-cycle catch swallows
            // that. Defer so the outcome is identical without latching the memo off for the
            // session.
            return -1;
        }
        if (c != currentConnection) {
            switchConnection(c);
        }
        if (currentSeesWorldMap) {
            return 1;
        }
        if (mode == ALL) {
            return 1;
        }
        if (mode == FACTION_AND_VISIBLE_ONLY) {
            // Vanilla iterates c.players without a null check; on the server checkCanSeeClient is
            // unconditionally true, so a non-null slot 0 short-circuits to true and a null slot 0
            // NPEs. Defer the latter to vanilla so the outcome (and exception) is identical
            // without latching the memo off.
            for (IsoPlayer connectedPlayer : c.players) {
                if (connectedPlayer == null) {
                    return -1;
                }
                if (connectedPlayer.checkCanSeeClient(player)) {
                    return 1;
                }
            }
        }
        if (mode != FACTION_AND_VISIBLE_ONLY && mode != FACTION_ONLY) {
            return 0;
        }
        Member remote = TABLES.get(player.getUsername());
        for (int i = 0; i < CONNECTION_MEMBERS.length; i++) {
            if (Tables.sameFaction(CONNECTION_MEMBERS[i], remote)) {
                return 1;
            }
        }
        for (int i = 0; i < CONNECTION_MEMBERS.length; i++) {
            if (Tables.shareSafehouse(CONNECTION_MEMBERS[i], remote)) {
                return 1;
            }
        }
        return 0;
    }

    private static void switchConnection(UdpConnection c) {
        currentConnection = c;
        currentSeesWorldMap = c.getRole().hasCapability(Capability.SeeWorldMap);
        for (int i = 0; i < CONNECTION_MEMBERS.length; i++) {
            IsoPlayer p = c.players[i];
            CONNECTION_MEMBERS[i] = p == null ? null : TABLES.get(p.getUsername());
        }
    }

    private static void fail(String stage, Throwable t) {
        disabled = true;
        active = false;
        WorldMapVisibilityMemoMetrics.recordFailure();
        LOGGER.error(
                "Storm: world-map visibility memo failed during {} — reverting to the vanilla"
                        + " predicate for the rest of this session",
                stage,
                t);
    }

    static boolean isActiveForTest() {
        return active;
    }

    static void resetForTest() {
        end();
        disabled = false;
    }

    /** One username's faction (first match, vanilla order) and safehouse memberships. */
    static final class Member {
        Faction faction;
        int[] safehouses = new int[2];
        int safehouseCount;

        void addSafehouse(int id) {
            if (safehouseCount == safehouses.length) {
                int[] grown = new int[safehouses.length * 2];
                System.arraycopy(safehouses, 0, grown, 0, safehouseCount);
                safehouses = grown;
            }
            safehouses[safehouseCount++] = id;
        }
    }

    /** Username-keyed view of the faction and safehouse lists. PZ-static-free; unit tested. */
    static final class Tables {
        private final HashMap<String, Member> members = new HashMap<>();

        void clear() {
            members.clear();
        }

        Member get(String username) {
            return username == null ? null : members.get(username);
        }

        int size() {
            return members.size();
        }

        /**
         * Mirrors {@code Faction.getPlayerFaction}: the first faction in list order whose owner or
         * member list contains the name wins, so call in list order and only the first claim
         * sticks.
         */
        void indexFaction(Faction faction) {
            claimFaction(faction.getOwner(), faction);
            List<String> players = faction.getPlayers();
            for (int i = 0; i < players.size(); i++) {
                claimFaction(players.get(i), faction);
            }
        }

        private void claimFaction(String username, Faction faction) {
            if (username == null) {
                return;
            }
            Member member = member(username);
            if (member.faction == null) {
                member.faction = faction;
            }
        }

        /** Mirrors {@code SafeHouse.playerAllowed(String)}: owner or listed player. */
        void indexSafehouse(int id, String owner, List<String> players) {
            if (owner != null) {
                member(owner).addSafehouse(id);
            }
            for (int i = 0; i < players.size(); i++) {
                String player = players.get(i);
                if (player != null) {
                    member(player).addSafehouse(id);
                }
            }
        }

        private Member member(String username) {
            Member member = members.get(username);
            if (member == null) {
                member = new Member();
                members.put(username, member);
            }
            return member;
        }

        /** {@code isInSameFaction}: both resolve to the same non-null faction. */
        static boolean sameFaction(Member a, Member b) {
            return a != null && b != null && a.faction != null && a.faction == b.faction;
        }

        /** {@code isInSameSafehouse}: some safehouse allows both names. */
        static boolean shareSafehouse(Member a, Member b) {
            if (a == null || b == null) {
                return false;
            }
            for (int i = 0; i < a.safehouseCount; i++) {
                int id = a.safehouses[i];
                for (int j = 0; j < b.safehouseCount; j++) {
                    if (b.safehouses[j] == id) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
