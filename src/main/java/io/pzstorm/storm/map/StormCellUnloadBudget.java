package io.pzstorm.storm.map;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.CellUnloadBudgetMetrics;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import io.pzstorm.storm.patch.performance.StormCellWarmingConfig;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.GameServer;
import zombie.network.ServerChunkLoader;
import zombie.network.ServerLOS;
import zombie.network.ServerMap;
import zombie.popman.NetworkZombiePacker;

/**
 * Budgeted replacement for the body of {@code ServerMap.postupdate()}, wired in by {@code
 * ServerMapPostUpdateBudgetPatch}.
 *
 * <p>Vanilla postupdate unloads <em>every</em> loaded cell that fails the relevance check ({@code
 * releventNow.contains(cell) || !outsidePlayerInfluence(cell)}) in a single tick. Each {@code
 * ServerCell.Unload()} walks 64 chunks &rarr; {@code IsoChunk.removeFromWorld()} &rarr; per-entity
 * {@code EngineEntityManager.removeEntityInternal}, whose {@code Array.removeValue} is a linear
 * identity scan of the global entity array (~123k entities live on the profiled server). When
 * several players log off or move at once, dozens of cells go stale simultaneously and the burst
 * shows up as multi-hundred-ms tick spikes (~11% of the main thread during a burst window,
 * near-zero steady-state).
 *
 * <p>This helper reimplements the vanilla loop faithfully — same relevance predicate, same
 * cancel-loading bookkeeping for cells still in flight (including the {@code DebugType.MapLoading}
 * log line), same {@code cell.update()} for relevant cells, same {@code
 * ServerLOS.instance.suspend()}-before-first-unload / resume-in-finally bracket, same {@code catch
 * (Exception) -> DebugType.General.printException} swallow, and the same tail calls ({@code
 * NetworkZombiePacker.postupdate()} + {@code chunkLoader.updateSaved()}) — but unloads at most
 * {@code Storm.CellUnloadBudgetPerTick} cells per call. Stale cells beyond the budget simply stay
 * in {@code loadedCells}; relevance is re-evaluated every tick, so a deferred cell that becomes
 * relevant again is naturally kept, and one that stays stale is unloaded on a following tick.
 * Deferred cells are <em>not</em> {@code update()}d — vanilla would have destroyed them this tick,
 * so nothing may rely on them ticking. Deferral is safe: {@code loadedCells} is bounded by the
 * relevance radius of connected players, save paths ({@code SaveAll}/{@code QueuedSaveAll}) iterate
 * {@code loadedCells} and save deferred cells like any other loaded cell, and {@code
 * ServerCell.Unload()} queues its chunk save jobs whenever the cell eventually unloads.
 *
 * <p>Composes with the other postupdate patches: the {@code ServerMapPostUpdatePatch} stopwatch is
 * registered <em>after</em> the budget patch so its advice wraps the skip and {@code
 * pz_server_map_post_update_call_duration_seconds} times both paths; {@code
 * ServerMapPostUpdateWarmPatch} is registered later still (outermost) and owns the whole body when
 * {@code -Dstorm.cells.keepWarm=true} — {@link #runBudgeted(Object)} defers to it explicitly via
 * the {@link StormCellWarmingConfig#isEnabled()} gate so exactly one body replacement can run.
 * {@code ServerCellUnloadPatch} and {@code IsoChunkRemoveFromWorldPatch} advise methods this helper
 * calls, so their timing metrics keep recording on the budgeted path.
 *
 * <p>Vanilla behavior is restored wholesale with {@code Storm.CellUnloadBudgetPerTick = 0}
 * (live-appliable), and permanently if the budgeted body ever throws outside the vanilla-replicated
 * {@code catch (Exception)}.
 *
 * <p>Single-threaded by design: {@code ServerMap.postupdate()} only runs on the server main thread,
 * so the metrics tallies and the failure latch need no synchronization.
 */
public final class StormCellUnloadBudget {

    /** Default for {@code Storm.CellUnloadBudgetPerTick}: at most 2 cell unloads per tick. */
    public static final int DEFAULT_BUDGET = 2;

    /** 0 disables the budget entirely — vanilla unload-everything-now behavior. */
    public static final int MIN_BUDGET = 0;

    /** Hard ceiling; above this the budget no longer meaningfully bounds a burst. */
    public static final int MAX_BUDGET = 32;

    /**
     * Live budget, driven by the {@code Storm.CellUnloadBudgetPerTick} sandbox option through
     * {@link #setBudgetPerTick(int)}. Volatile because the sandbox applier may push updates from
     * outside the main thread; the per-call read is a single volatile load.
     */
    private static volatile int currentBudget = DEFAULT_BUDGET;

    /** Permanent revert-to-vanilla latch; set on the first {@link Throwable} out of the body. */
    private static boolean failed;

    private static volatile boolean initialized;

    /** {@code private boolean ServerMap$ServerCell.startedLoading}. */
    private static Field startedLoadingField;

    /** {@code private static boolean ServerMap.mapLoading}, refreshed by vanilla preupdate. */
    private static Field mapLoadingField;

    /** {@code private static ServerChunkLoader ServerMap$ServerCell.chunkLoader}. */
    private static ServerChunkLoader chunkLoader;

    private StormCellUnloadBudget() {}

    /**
     * Live-updates the per-tick unload budget, clamping to {@link #MIN_BUDGET}..{@link
     * #MAX_BUDGET}, and pushes the applied value to the Prometheus gauge. Single mutation point —
     * sandbox apply and tests both funnel through here.
     *
     * @return the value actually applied
     */
    public static int setBudgetPerTick(int requested) {
        int applied = clamp(requested);
        currentBudget = applied;
        StormPerformanceSandboxMetrics.setCellUnloadBudgetPerTick(applied);
        LOGGER.info("Storm: cell-unload budget per tick updated to {}", applied);
        return applied;
    }

    /** Current effective budget; 0 = vanilla/unlimited. */
    public static int getBudgetPerTick() {
        return currentBudget;
    }

    /**
     * Runs the budgeted postupdate body for {@code serverMapObj}.
     *
     * @param serverMapObj the {@code ServerMap} ({@code @Advice.This}; typed {@code Object} so the
     *     advice never references the transform target).
     * @return {@code true} if the budgeted body ran (the advice skips the vanilla body); {@code
     *     false} to fall through to vanilla (budget 0, cell warming owns the body, or the failure
     *     latch is set).
     */
    public static boolean runBudgeted(Object serverMapObj) {
        int budget = currentBudget;
        if (failed || budget <= 0) {
            return false;
        }
        if (StormCellWarmingConfig.isEnabled()) {
            // The warm advice (registered outermost) body-replaces postupdate itself; with
            // warming on it intercepts before this advice ever runs. This gate only matters if
            // that registration order changes — exactly one body replacement may run.
            return false;
        }
        try {
            ServerMap serverMap = (ServerMap) serverMapObj;
            ensureInit();
            run(serverMap, budget);
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error(
                    "StormCellUnloadBudget failed — reverting to vanilla ServerMap.postupdate."
                            + " The vanilla body runs for the rest of this tick; its loop"
                            + " re-evaluates loadedCells from scratch, so partial work done by the"
                            + " budgeted pass is completed rather than repeated.",
                    t);
            return false;
        }
    }

    /**
     * The vanilla {@code ServerMap.postupdate()} body with an unload budget. Every kept statement
     * mirrors its vanilla counterpart in order; the only divergences are the {@code unloaded >=
     * budget} deferral and the metrics flush at the end.
     */
    private static void run(ServerMap serverMap, int budget) throws IllegalAccessException {
        boolean pathfindPaused = false;
        // Vanilla reads the static mapLoading flag (set once per preupdate) on the cancel branch.
        boolean mapLoadingLog = mapLoadingField.getBoolean(null);
        long unloaded = 0;
        long deferred = 0;
        ArrayList<ServerMap.ServerCell> loadedCells = serverMap.loadedCells;
        ArrayList<ServerMap.ServerCell> releventNow = serverMap.releventNow;

        try {
            for (int n = 0; n < loadedCells.size(); n++) {
                ServerMap.ServerCell cell = loadedCells.get(n);
                boolean shouldBeLoaded =
                        releventNow.contains(cell) || !outsidePlayerInfluence(cell);
                if (!cell.isLoaded) {
                    if (!shouldBeLoaded && !cell.cancelLoading) {
                        if (mapLoadingLog) {
                            DebugLog.log(
                                    DebugType.MapLoading,
                                    "MainThread: cancelling "
                                            + cell.wx
                                            + ","
                                            + cell.wy
                                            + " cell.startedLoading="
                                            + startedLoadingField.getBoolean(cell));
                        }

                        if (!startedLoadingField.getBoolean(cell)) {
                            cell.loadingWasCancelled = true;
                        }

                        cell.cancelLoading = true;
                    }
                } else if (!shouldBeLoaded) {
                    if (unloaded >= budget) {
                        // Budget exhausted: leave the cell in loadedCells for a later tick.
                        // Not update()d — vanilla would have destroyed it this tick.
                        deferred++;
                        continue;
                    }
                    int x = cell.wx - serverMap.getMinX();
                    int y = cell.wy - serverMap.getMinY();
                    int width = serverMap.getMaxX() - serverMap.getMinX() + 1;
                    if (!pathfindPaused) {
                        ServerLOS.instance.suspend();
                        pathfindPaused = true;
                    }

                    serverMap.cellMap[y * width + x].Unload();
                    serverMap.cellMap[y * width + x] = null;
                    loadedCells.remove(cell);
                    n--;
                    unloaded++;
                } else {
                    cell.update();
                }
            }
        } catch (Exception e) {
            // Vanilla-identical swallow — postupdate tolerates per-cell exceptions and moves on.
            DebugType.General.printException(e, LogSeverity.Error);
        } finally {
            if (pathfindPaused) {
                ServerLOS.instance.resume();
            }
        }

        NetworkZombiePacker.getInstance().postupdate();
        chunkLoader.updateSaved();
        CellUnloadBudgetMetrics.record(unloaded, deferred);
    }

    // Re-implementation of the private ServerMap.outsidePlayerInfluence(ServerCell). Kept in sync
    // with vanilla (four corner relevance probes against every connection).
    private static boolean outsidePlayerInfluence(ServerMap.ServerCell cell) {
        int x1 = cell.wx * 64;
        int y1 = cell.wy * 64;
        int x2 = (cell.wx + 1) * 64;
        int y2 = (cell.wy + 1) * 64;
        List<UdpConnection> connections = GameServer.udpEngine.connections;
        for (int n = 0; n < connections.size(); n++) {
            UdpConnection c = connections.get(n);
            if (c.isRelevantTo(x1, y1)
                    || c.isRelevantTo(x2, y1)
                    || c.isRelevantTo(x2, y2)
                    || c.isRelevantTo(x1, y2)) {
                return false;
            }
        }
        return true;
    }

    private static void ensureInit() throws ReflectiveOperationException {
        if (initialized) {
            return;
        }
        synchronized (StormCellUnloadBudget.class) {
            if (initialized) {
                return;
            }
            startedLoadingField = ServerMap.ServerCell.class.getDeclaredField("startedLoading");
            startedLoadingField.setAccessible(true);
            mapLoadingField = ServerMap.class.getDeclaredField("mapLoading");
            mapLoadingField.setAccessible(true);
            Field cl = ServerMap.ServerCell.class.getDeclaredField("chunkLoader");
            cl.setAccessible(true);
            chunkLoader = (ServerChunkLoader) cl.get(null);
            initialized = true;
            LOGGER.info("StormCellUnloadBudget: ServerMap reflection bridge initialized");
        }
    }

    private static int clamp(int requested) {
        if (requested < MIN_BUDGET) {
            LOGGER.warn(
                    "Storm: cell-unload budget {} below floor, clamping to {}",
                    requested,
                    MIN_BUDGET);
            return MIN_BUDGET;
        } else if (requested > MAX_BUDGET) {
            LOGGER.warn(
                    "Storm: cell-unload budget {} above ceiling, clamping to {}",
                    requested,
                    MAX_BUDGET);
            return MAX_BUDGET;
        }
        return requested;
    }
}
