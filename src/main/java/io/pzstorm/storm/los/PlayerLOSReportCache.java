package io.pzstorm.storm.los;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side cache of the latest client-Lua LOS report per player. Phase 2 uses this for
 * server-side parity diffing (server's just-computed {@code spottedList} vs the latest cached
 * client view); Phase 4 will reuse it as the source for the {@code updateLOS} substitution.
 */
public final class PlayerLOSReportCache {

    public static final PlayerLOSReportCache INSTANCE = new PlayerLOSReportCache();

    private final Map<Short, Report> reports = new ConcurrentHashMap<>();

    private PlayerLOSReportCache() {}

    public void put(Report report) {
        reports.put(report.playerOnlineID, report);
    }

    public Report get(short onlineID) {
        return reports.get(onlineID);
    }

    public void remove(short onlineID) {
        reports.remove(onlineID);
    }

    public int size() {
        return reports.size();
    }

    /** Latest entry for a single player. {@code arrivedMs} is stamped on receipt by the server. */
    public static final class Report {

        public final short playerOnlineID;
        public final long clientTick;
        public final long clientWallMs;
        public final long arrivedMs;
        public final boolean selfSpotted;
        public final boolean truncated;
        public final short[] ids;
        public final boolean[] couldSee;
        public final boolean[] canSee;

        public Report(
                short playerOnlineID,
                long clientTick,
                long clientWallMs,
                long arrivedMs,
                boolean selfSpotted,
                boolean truncated,
                short[] ids,
                boolean[] couldSee,
                boolean[] canSee) {
            this.playerOnlineID = playerOnlineID;
            this.clientTick = clientTick;
            this.clientWallMs = clientWallMs;
            this.arrivedMs = arrivedMs;
            this.selfSpotted = selfSpotted;
            this.truncated = truncated;
            this.ids = ids;
            this.couldSee = couldSee;
            this.canSee = canSee;
        }
    }
}
