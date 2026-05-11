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

    /**
     * Default freshness window for {@link #getLatest(short, long, long)} callers that don't pass an
     * explicit one. Chosen for ~3 server ticks at 10 TPS (Phase 4 doc default). A report older than
     * this is rejected and the consumer falls back to vanilla LOS for that tick.
     */
    public static final long DEFAULT_MAX_REPORT_AGE_MS = 300L;

    private final Map<Short, Report> reports = new ConcurrentHashMap<>();

    private PlayerLOSReportCache() {}

    public void put(Report report) {
        reports.put(report.playerOnlineID, report);
    }

    public Report get(short onlineID) {
        return reports.get(onlineID);
    }

    /**
     * Returns the latest report for {@code onlineID} only if it arrived within {@code maxAgeMs}
     * milliseconds of {@code nowMs}. Returns {@code null} otherwise so the caller can fall back to
     * vanilla LOS without doing its own age math.
     */
    public Report getLatest(short onlineID, long nowMs, long maxAgeMs) {
        Report r = reports.get(onlineID);
        if (r == null) {
            return null;
        }
        if (nowMs - r.arrivedMs > maxAgeMs) {
            return null;
        }
        return r;
    }

    public Report getLatest(short onlineID) {
        return getLatest(onlineID, System.currentTimeMillis(), DEFAULT_MAX_REPORT_AGE_MS);
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
