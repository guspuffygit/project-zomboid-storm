package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.ImportantAreasMetrics;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import java.util.LinkedList;
import java.util.List;
import zombie.core.ImportantArea;
import zombie.core.math.PZMath;
import zombie.network.GameServer;

/**
 * Server-side replacement for the body of {@code ImportantAreaManager.updateOrAdd}: the engine's
 * list of map areas that must stay loaded with nobody near them, with its cap made configurable and
 * its eviction made deterministic.
 *
 * <h2>What the list is</h2>
 *
 * <p>An important area is a 64x64-tile square (one {@code ServerMap} cell). Two engine callers book
 * one every tick: {@code IsoStove.update} while a stove, oven, barbecue or fireplace is lit with
 * something to cook, and {@code BaseVehicle.updateImportantAreas} while a vehicle's engine, alarm
 * or siren is running. {@code ImportantAreaManager.process} then keeps each booked cell resident
 * and drops an entry ten seconds after its last refresh.
 *
 * <h2>The two vanilla decisions this replaces</h2>
 *
 * <p>{@code importantAreasMaximum} is {@code public static final int 100}, so {@code javac} inlined
 * it and the method compares against a literal: there is no way to change the cap without patching
 * the comparison, which is what this does. A hundred is small for a populated server before any
 * warming or any mod is involved: a connection's relevance range is {@code relevantRange * 8} tiles
 * with {@code relevantRange} in {@code 8..12}, so every player pins a 3x3-to-4x4 block of cells
 * that never unloads, and every lit stove or idling car in those blocks wants a slot.
 *
 * <p>At the cap vanilla does {@code ImportantAreas.remove(Rand.Next(0, size))} and returns {@code
 * null}: a random incumbent loses its slot, whoever asked gets nothing, and the size oscillates
 * 99/100 for as long as more than a hundred things are booking. The victim is as likely to be the
 * entry refreshed this millisecond as the one nine seconds from expiring on its own. This policy
 * evicts the least-recently-refreshed entry instead, which is the entry closest to timing out
 * anyway; the rest of vanilla's shape is kept exactly, including returning {@code null} to the
 * caller rather than adding it in the freed slot (adding it would double the per-tick churn under
 * contention, since every miss would then evict, where vanilla alternates evict and add).
 *
 * <h2>The option</h2>
 *
 * <p>{@code Storm.ImportantAreasMaximum}, {@value #MIN_MAXIMUM}..{@value #MAX_MAXIMUM}, default
 * {@value #VANILLA_MAXIMUM}. Live on an admin sandbox push; a lowered cap trims one entry per miss
 * rather than in one go, as vanilla would. Raising it is not free: each entry keeps a full cell
 * resident, so it trades heap and tick against cooking stopping in random places. The
 * least-recently-refreshed eviction applies at every value including the default; the vanilla
 * warning line is kept (same leading words, so an existing log grep still finds it) but emitted at
 * most once a second with the number of evictions since the last line.
 *
 * <h2>Scope and failure</h2>
 *
 * <p>Registered under {@code StormEnv.isStormServer()} and gated again on {@code
 * GameServer.server}, so a co-op host's client half keeps vanilla. Any throwable inside the
 * decision latches the policy off for the rest of the boot and the vanilla body runs; the fail-safe
 * direction is vanilla, which the server already survives.
 */
public final class ImportantAreasPolicy {

    /** Vanilla's inlined {@code importantAreasMaximum}. */
    public static final int VANILLA_MAXIMUM = 100;

    /** Floor. Below vanilla would unload cooking that vanilla keeps, which nobody wants. */
    public static final int MIN_MAXIMUM = 100;

    /**
     * Ceiling. Each entry pins a 64-chunk cell resident; this matches {@code Storm.MaxWarmCells}.
     */
    public static final int MAX_MAXIMUM = 2048;

    /** Area side in tiles: vanilla's {@code PZMath.coorddivision(x, 64)}, one ServerMap cell. */
    public static final int AREA_TILES = 64;

    /** At most one warning line per this many milliseconds while the list is at its cap. */
    static final long WARN_INTERVAL_MS = 1000L;

    /**
     * Handed back by {@link #decide} when the cap was hit: the advice returns vanilla's {@code
     * null} to the caller. A sentinel rather than {@code null} because {@code null} from the advice
     * means "run the vanilla body".
     */
    public static final Object EVICTED = new Object();

    private static volatile int currentMaximum = VANILLA_MAXIMUM;
    private static volatile boolean failureLatched;

    // Main-thread state for the rate-limited warning; updateOrAdd is only ever called from the
    // server main thread (IsoWorld.update and the vehicle update both run there).
    private static long lastWarnMs = Long.MIN_VALUE;
    private static long evictionsSinceWarn;

    private ImportantAreasPolicy() {}

    /** Cap currently applied. {@value #VANILLA_MAXIMUM} is vanilla's. */
    public static int getMaximum() {
        return currentMaximum;
    }

    /** True once an unexpected error has handed the method back to vanilla for this boot. */
    public static boolean isFailureLatched() {
        return failureLatched;
    }

    /** Pure: the request clamped to {@link #MIN_MAXIMUM}..{@link #MAX_MAXIMUM}. */
    public static int clampMaximum(int requested) {
        if (requested < MIN_MAXIMUM) {
            LOGGER.warn(
                    "Storm: important areas maximum {} below floor, clamping to {}",
                    requested,
                    MIN_MAXIMUM);
            return MIN_MAXIMUM;
        }
        if (requested > MAX_MAXIMUM) {
            LOGGER.warn(
                    "Storm: important areas maximum {} above ceiling, clamping to {}",
                    requested,
                    MAX_MAXIMUM);
            return MAX_MAXIMUM;
        }
        return requested;
    }

    /** Live-updates the cap, clamping the request. Returns the value actually applied. */
    public static int setMaximum(int requested) {
        int applied = clampMaximum(requested);
        currentMaximum = applied;
        StormPerformanceSandboxMetrics.setImportantAreasMaximum(applied);
        if (applied == VANILLA_MAXIMUM) {
            LOGGER.info(
                    "Storm: important areas maximum {} (vanilla cap; eviction is"
                            + " least-recently-refreshed rather than random)",
                    applied);
        } else {
            LOGGER.info("Storm: important areas maximum updated to {} (vanilla 100)", applied);
        }
        return applied;
    }

    /**
     * The decision, called from the enter advice on {@code ImportantAreaManager.updateOrAdd} with
     * the instrumented class's own {@code ImportantAreas} list. Returns {@code null} to let the
     * vanilla body run, {@link #EVICTED} when the cap was hit (the caller gets {@code null}), or
     * the {@link ImportantArea} the caller should get.
     */
    public static Object decide(LinkedList<ImportantArea> areas, int x, int y) {
        if (failureLatched || areas == null || !GameServer.server) {
            return null;
        }
        try {
            ImportantArea area =
                    updateOrAdd(areas, x, y, currentMaximum, System.currentTimeMillis());
            return area == null ? EVICTED : area;
        } catch (Throwable t) {
            latch(t);
            return null;
        }
    }

    /**
     * Pure: vanilla's {@code updateOrAdd} with the cap as a parameter and the eviction
     * least-recently-refreshed. Refreshes and returns an existing entry for the area; else at or
     * above {@code maximum} evicts the entry with the oldest {@code lastUpdate} and returns {@code
     * null}, as vanilla does; else adds and returns a new entry stamped {@code now}.
     */
    static ImportantArea updateOrAdd(
            LinkedList<ImportantArea> areas, int x, int y, int maximum, long now) {
        int sx = PZMath.coorddivision(x, AREA_TILES);
        int sy = PZMath.coorddivision(y, AREA_TILES);
        for (ImportantArea area : areas) {
            if (area.sx == sx && area.sy == sy) {
                area.lastUpdate = now;
                ImportantAreasMetrics.size = areas.size();
                return area;
            }
        }
        if (areas.size() >= maximum) {
            int victim = indexOfLeastRecentlyRefreshed(areas);
            if (victim >= 0) {
                areas.remove(victim);
            }
            ImportantAreasMetrics.evictions++;
            ImportantAreasMetrics.size = areas.size();
            warnAtCap(maximum, now);
            return null;
        }
        ImportantArea added = new ImportantArea(sx, sy);
        added.lastUpdate = now;
        areas.add(added);
        ImportantAreasMetrics.size = areas.size();
        return added;
    }

    /**
     * Index of the entry with the oldest {@code lastUpdate}; ties go to the earliest in the list,
     * which is the earliest booked. {@code -1} for an empty list.
     */
    static int indexOfLeastRecentlyRefreshed(List<ImportantArea> areas) {
        int victim = -1;
        long oldest = Long.MAX_VALUE;
        int i = 0;
        for (ImportantArea area : areas) {
            if (area.lastUpdate < oldest) {
                oldest = area.lastUpdate;
                victim = i;
            }
            i++;
        }
        return victim;
    }

    private static void warnAtCap(int maximum, long now) {
        evictionsSinceWarn++;
        if (lastWarnMs != Long.MIN_VALUE && now - lastWarnMs < WARN_INTERVAL_MS) {
            return;
        }
        LOGGER.warn(
                "ImportantAreas size is too big (Storm.ImportantAreasMaximum {}). The"
                        + " least-recently-refreshed map area will unload; {} eviction(s) since the"
                        + " last line",
                maximum,
                evictionsSinceWarn);
        ImportantAreasMetrics.warnings++;
        lastWarnMs = now;
        evictionsSinceWarn = 0;
    }

    private static void latch(Throwable t) {
        failureLatched = true;
        LOGGER.error(
                "Storm: important areas policy disabled for this boot after an unexpected error;"
                        + " vanilla ImportantAreaManager.updateOrAdd restored",
                t);
    }

    /** Test-only: back to a freshly booted state. */
    static void resetForTest() {
        currentMaximum = VANILLA_MAXIMUM;
        failureLatched = false;
        lastWarnMs = Long.MIN_VALUE;
        evictionsSinceWarn = 0;
    }

    /** Test-only: drives the failure latch without needing something to actually throw. */
    static void latchForTest() {
        failureLatched = true;
    }
}
