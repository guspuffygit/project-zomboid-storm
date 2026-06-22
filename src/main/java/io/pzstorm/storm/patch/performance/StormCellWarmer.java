package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnChunkRewarmedEvent;
import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.StormCellWarmingMetrics;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import zombie.MapCollisionData;
import zombie.characters.animals.AnimalPopulationManager;
import zombie.characters.animals.IsoAnimal;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDeadBody;
import zombie.network.GameServer;
import zombie.network.ServerChunkLoader;
import zombie.network.ServerLOS;
import zombie.network.ServerMap;
import zombie.network.id.IIdentifiable;
import zombie.network.id.ObjectIDManager;
import zombie.network.id.ObjectIDType;
import zombie.pathfind.PolygonalMap2;
import zombie.pathfind.nativeCode.PathfindNative;
import zombie.popman.NetworkZombiePacker;
import zombie.popman.ZombiePopulationManager;

/**
 * Server-side cell warming helper.
 *
 * <p>Design: warm cells stay in {@code ServerMap.cellMap} and {@code ServerMap.loadedCells} with
 * {@code cell.isLoaded == true}. Only the world-system bindings (collision/pathfind/animal/zombie
 * pop) are disconnected; the chunk grid, vehicles, room data, and zone bindings stay resident. This
 * means {@code ServerMap.getCell(...)} and {@code ServerMap.getChunk(...)} keep returning the
 * cell/chunks while warm, so client chunk-stream requests, AI pathfinding, line-of-sight, vehicle
 * code, etc. all continue to see the live in-memory state instead of getting nulls (which would
 * route them through stale-disk-read or {@code sendNotRequired} fallbacks).
 *
 * <p>The decision to warm vs. destructively unload, and the rewarm-on-relevance, both happen inside
 * {@link #runPostUpdate(ServerMap)} which body-replaces vanilla {@code ServerMap.postupdate}.
 * {@code ServerCell.Unload} stays untouched (vanilla destructive behavior) because it's used by the
 * shutdown save flow; warming is invoked from postupdate only.
 *
 * <p>Gated server-side on {@link StormCellWarmingConfig#isEnabled()}. Single-threaded — all calls
 * happen from the server main thread.
 */
public final class StormCellWarmer {

    private static final Map<Long, WarmCell> WARM_CELLS = new HashMap<>();

    // ServerCell.chunkLoader and ServerCell.startedLoading are private; reach them once at
    // class-load so the body-replaced postupdate can still drive the save-job pump that vanilla
    // does at its tail and check the same loading-cancellation predicate as vanilla.
    private static final ServerChunkLoader CHUNK_LOADER;
    private static final Field STARTED_LOADING;

    static {
        try {
            Field cl = ServerMap.ServerCell.class.getDeclaredField("chunkLoader");
            cl.setAccessible(true);
            CHUNK_LOADER = (ServerChunkLoader) cl.get(null);
            STARTED_LOADING = ServerMap.ServerCell.class.getDeclaredField("startedLoading");
            STARTED_LOADING.setAccessible(true);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private static boolean startedLoading(ServerMap.ServerCell cell) {
        try {
            return STARTED_LOADING.getBoolean(cell);
        } catch (IllegalAccessException e) {
            // Should never happen — setAccessible succeeded at class-load.
            throw new RuntimeException(e);
        }
    }

    private static final class WarmAnimal {
        final IsoAnimal animal;
        final IsoGridSquare originalSquare;

        WarmAnimal(IsoAnimal animal, IsoGridSquare originalSquare) {
            this.animal = animal;
            this.originalSquare = originalSquare;
        }
    }

    private static final class WarmCell {
        final ServerMap.ServerCell cell;
        final long warmedAtNanos;
        final List<WarmAnimal> animals;
        final List<IsoDeadBody> deadBodies;

        WarmCell(
                ServerMap.ServerCell cell,
                long warmedAtNanos,
                List<WarmAnimal> animals,
                List<IsoDeadBody> deadBodies) {
            this.cell = cell;
            this.warmedAtNanos = warmedAtNanos;
            this.animals = animals;
            this.deadBodies = deadBodies;
        }
    }

    private StormCellWarmer() {}

    private static long key(int wx, int wy) {
        return ((long) wx & 0xffffffffL) | (((long) wy & 0xffffffffL) << 32);
    }

    public static boolean isWarm(int wx, int wy) {
        return WARM_CELLS.containsKey(key(wx, wy));
    }

    public static boolean isWarm(ServerMap.ServerCell cell) {
        return WARM_CELLS.containsKey(key(cell.wx, cell.wy));
    }

    public static int warmCount() {
        return WARM_CELLS.size();
    }

    /**
     * Body-replacement for {@code ServerMap.postupdate}. Matches vanilla semantics for non-warm
     * cells and adds two warm-aware branches:
     *
     * <ul>
     *   <li>When a non-warm cell would be vanilla-Unloaded (isLoaded &amp;&amp; !shouldBeLoaded),
     *       try {@link #warm(ServerMap.ServerCell)} first. On success the cell stays in {@code
     *       cellMap} and {@code loadedCells} with {@code isLoaded = true}; only its world-system
     *       bindings are detached.
     *   <li>When a warm cell becomes relevant again (isLoaded &amp;&amp; shouldBeLoaded), {@link
     *       #rewarm(ServerMap.ServerCell)} re-binds it before running {@code cell.update()}.
     * </ul>
     *
     * Called from {@code ServerMapPostUpdateWarmAdvice} which short-circuits the vanilla method
     * body when {@link StormCellWarmingConfig#isEnabled()}.
     */
    public static void runPostUpdate(ServerMap serverMap) {
        boolean pathfindPaused = false;
        ArrayList<ServerMap.ServerCell> loadedCells = serverMap.loadedCells;
        ArrayList<ServerMap.ServerCell> releventNow = serverMap.releventNow;
        try {
            for (int n = 0; n < loadedCells.size(); n++) {
                ServerMap.ServerCell cell = loadedCells.get(n);
                boolean shouldBeLoaded =
                        releventNow.contains(cell) || !outsidePlayerInfluence(cell);
                boolean warm = isWarm(cell);

                if (warm) {
                    if (shouldBeLoaded) {
                        rewarm(cell);
                        cell.update();
                    }
                    // else: stay warm — skip both Unload and update.
                    continue;
                }

                if (!cell.isLoaded) {
                    if (!shouldBeLoaded && !cell.cancelLoading) {
                        if (!startedLoading(cell)) {
                            cell.loadingWasCancelled = true;
                        }
                        cell.cancelLoading = true;
                    }
                } else if (!shouldBeLoaded) {
                    if (warm(cell)) {
                        // Warmed in-place: stays in cellMap/loadedCells with isLoaded=true.
                        continue;
                    }
                    // Warm refused (eligibility / soft-reset / throw) — vanilla destructive unload.
                    if (!pathfindPaused) {
                        ServerLOS.instance.suspend();
                        pathfindPaused = true;
                    }
                    int x = cell.wx - serverMap.getMinX();
                    int y = cell.wy - serverMap.getMinY();
                    int width = serverMap.getMaxX() - serverMap.getMinX() + 1;
                    serverMap.cellMap[y * width + x].Unload();
                    serverMap.cellMap[y * width + x] = null;
                    loadedCells.remove(cell);
                    n--;
                } else {
                    cell.update();
                }
            }
        } catch (Throwable t) {
            StormLogger.LOGGER.error("StormCellWarmer.runPostUpdate failed", t);
        } finally {
            if (pathfindPaused) {
                ServerLOS.instance.resume();
            }
        }

        NetworkZombiePacker.getInstance().postupdate();
        CHUNK_LOADER.updateSaved();
    }

    /**
     * Detach a cell's chunks from world-system bindings and stash dynamic state. Keeps the cell
     * itself addressable: {@code cellMap[idx]}, {@code loadedCells}, and {@code cell.isLoaded =
     * true} are unchanged. Returns {@code false} if the cell isn't eligible — caller must fall
     * through to vanilla destructive unload.
     */
    public static boolean warm(ServerMap.ServerCell cell) {
        String reason = ineligibleReason(cell);
        if (reason != null) {
            StormCellWarmingMetrics.incEligibilityFail(reason);
            return false;
        }
        if (!cell.isLoaded) {
            return false;
        }
        if (isWarm(cell)) {
            return true;
        }

        long now = System.nanoTime();
        List<WarmAnimal> animals = new ArrayList<>();
        List<IsoDeadBody> deadBodies = new ArrayList<>();
        int disconnectedX = -1, disconnectedY = -1;

        try {
            drainDeadBodies(cell, deadBodies);
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    IsoChunk chunk = cell.chunks[x][y];
                    if (chunk == null) {
                        continue;
                    }
                    drainAnimals(chunk, animals);
                    disconnectChunk(chunk);
                    disconnectedX = x;
                    disconnectedY = y;
                }
            }
        } catch (Throwable t) {
            StormLogger.LOGGER.error(
                    "StormCellWarmer.warm failed for cell {},{} — rolling back",
                    cell.wx,
                    cell.wy,
                    t);
            // Best-effort rollback: reconnect what we already disconnected and restore drained
            // state, so the cell can survive a vanilla destructive Unload from the caller.
            try {
                if (disconnectedX >= 0) {
                    outer:
                    for (int x = 0; x < 8; x++) {
                        for (int y = 0; y < 8; y++) {
                            if (x > disconnectedX || (x == disconnectedX && y > disconnectedY)) {
                                break outer;
                            }
                            IsoChunk chunk = cell.chunks[x][y];
                            if (chunk != null) {
                                reconnectChunk(chunk);
                            }
                        }
                    }
                }
                restoreAnimals(animals);
                restoreDeadBodies(deadBodies);
            } catch (Throwable rollbackErr) {
                StormLogger.LOGGER.error(
                        "StormCellWarmer.warm rollback also failed for cell {},{}",
                        cell.wx,
                        cell.wy,
                        rollbackErr);
            }
            return false;
        }

        WARM_CELLS.put(key(cell.wx, cell.wy), new WarmCell(cell, now, animals, deadBodies));
        StormCellWarmingMetrics.incCellsWarmed();
        StormCellWarmingMetrics.setWarmCount(WARM_CELLS.size());
        StormCellWarmingMetrics.recordWarmOpNanos(System.nanoTime() - now);
        return true;
    }

    /**
     * Re-attach a warm cell's chunks to world systems and restore the animal/dead-body stash. The
     * cell itself never left {@code cellMap}/{@code loadedCells}, so no map mutation is needed
     * here. Returns {@code false} only on internal error (cell is put back into {@code WARM_CELLS}
     * so we don't leak it).
     */
    public static boolean rewarm(ServerMap.ServerCell cell) {
        WarmCell warm = WARM_CELLS.remove(key(cell.wx, cell.wy));
        if (warm == null) {
            return false;
        }
        long opStart = System.nanoTime();
        try {
            for (int cx = 0; cx < 8; cx++) {
                for (int cy = 0; cy < 8; cy++) {
                    IsoChunk chunk = cell.chunks[cx][cy];
                    if (chunk != null) {
                        reconnectChunk(chunk);
                    }
                }
            }
            restoreAnimals(warm.animals);
            restoreDeadBodies(warm.deadBodies);

            for (int cx = 0; cx < 8; cx++) {
                for (int cy = 0; cy < 8; cy++) {
                    IsoChunk chunk = cell.chunks[cx][cy];
                    if (chunk != null) {
                        StormEventDispatcher.dispatchEvent(new OnChunkRewarmedEvent(chunk));
                    }
                }
            }

            long opEnd = System.nanoTime();
            StormCellWarmingMetrics.incCellsRewarmed();
            StormCellWarmingMetrics.recordWarmDurationNanos(opEnd - warm.warmedAtNanos);
            StormCellWarmingMetrics.recordRewarmOpNanos(opEnd - opStart);
            StormCellWarmingMetrics.setWarmCount(WARM_CELLS.size());
            return true;
        } catch (Throwable t) {
            StormLogger.LOGGER.error(
                    "StormCellWarmer.rewarm failed for cell {},{} — leaving in warm state",
                    cell.wx,
                    cell.wy,
                    t);
            WARM_CELLS.put(key(cell.wx, cell.wy), warm);
            StormCellWarmingMetrics.setWarmCount(WARM_CELLS.size());
            return false;
        }
    }

    // Re-implementation of ServerMap.outsidePlayerInfluence(ServerCell) which is private. Kept
    // byte-for-byte in sync with vanilla — used only inside runPostUpdate's body replacement.
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

    private static String ineligibleReason(ServerMap.ServerCell cell) {
        if (GameServer.softReset) {
            return "soft_reset";
        }
        ServerMap sm = ServerMap.instance;
        if (sm == null) {
            return "no_server_map";
        }
        if (sm.queuedQuit || sm.queuedSaveAll) {
            return "save_or_quit_queued";
        }
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                IsoChunk chunk = cell.chunks[x][y];
                if (chunk != null && chunk.jobType == IsoChunk.JobType.SoftReset) {
                    return "chunk_soft_reset";
                }
            }
        }
        return null;
    }

    private static void drainAnimals(IsoChunk chunk, List<WarmAnimal> sink) {
        for (int z = chunk.getMinLevel(); z <= chunk.getMaxLevel(); z++) {
            int zIdx = chunk.squaresIndexOfLevel(z);
            if (zIdx < 0 || zIdx >= chunk.squares.length) {
                continue;
            }
            IsoGridSquare[] row = chunk.squares[zIdx];
            for (int i = 0; i < row.length; i++) {
                IsoGridSquare sq = row[i];
                if (sq == null) {
                    continue;
                }
                ArrayList<IsoMovingObject> mov = sq.getMovingObjects();
                for (int m = mov.size() - 1; m >= 0; m--) {
                    if (mov.get(m) instanceof IsoAnimal animal) {
                        animal.unloaded();
                        animal.setMovingSquare(null);
                        sink.add(new WarmAnimal(animal, sq));
                    }
                }
            }
        }
    }

    private static void disconnectChunk(IsoChunk chunk) {
        MapCollisionData.instance.removeChunkFromWorld(chunk);
        AnimalPopulationManager.getInstance().removeChunkFromWorld(chunk);
        ZombiePopulationManager.instance.removeChunkFromWorld(chunk);
        if (PathfindNative.useNativeCode) {
            PathfindNative.instance.removeChunkFromWorld(chunk);
        } else {
            PolygonalMap2.instance.removeChunkFromWorld(chunk);
        }
    }

    private static void reconnectChunk(IsoChunk chunk) {
        if (chunk.jobType == IsoChunk.JobType.SoftReset) {
            return;
        }
        MapCollisionData.instance.addChunkToWorld(chunk);
        AnimalPopulationManager.getInstance().addChunkToWorld(chunk);
        ZombiePopulationManager.instance.addChunkToWorld(chunk);
        if (PathfindNative.useNativeCode) {
            PathfindNative.instance.addChunkToWorld(chunk);
        } else {
            PolygonalMap2.instance.addChunkToWorld(chunk);
        }
    }

    private static void restoreAnimals(List<WarmAnimal> animals) {
        for (WarmAnimal stash : animals) {
            IsoAnimal animal = stash.animal;
            IsoGridSquare sq = stash.originalSquare;
            if (sq == null) {
                continue;
            }
            animal.setMovingSquare(sq);
            animal.updateLastTimeSinceUpdate();
        }
    }

    // Drain every dead body whose chunk lives in this warming cell, both from the global
    // ObjectIDType.DeadBody registry (so IsoDeadBody.updateBodies() stops ticking rot stages and
    // can't auto-remove them while warm) and from the staticUpdaterObjectList (so per-tick render
    // updaters skip them). The body's ObjectID is preserved on the body itself, so
    // ObjectIDManager.addObject(body) in restoreDeadBodies re-registers under the same ID and
    // network sync stays valid.
    private static void drainDeadBodies(ServerMap.ServerCell cell, List<IsoDeadBody> sink) {
        IsoCell isoCell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        ArrayList<IsoObject> updaters =
                isoCell == null ? null : isoCell.getStaticUpdaterObjectList();

        // Snapshot the registry view before mutating it — ObjectIDType.DeadBody.getObjects()
        // returns a live values() collection backed by the underlying HashMap.
        ArrayList<IIdentifiable> snapshot = new ArrayList<>(ObjectIDType.DeadBody.getObjects());
        for (IIdentifiable ii : snapshot) {
            if (!(ii instanceof IsoDeadBody body)) {
                continue;
            }
            IsoGridSquare sq = body.getSquare();
            if (sq == null || sq.chunk == null) {
                continue;
            }
            if (!chunkBelongsToCell(sq.chunk, cell)) {
                continue;
            }
            sink.add(body);
            ObjectIDManager.getInstance().remove(body.getObjectID());
            if (updaters != null) {
                updaters.remove(body);
            }
        }
    }

    private static void restoreDeadBodies(List<IsoDeadBody> bodies) {
        if (bodies.isEmpty()) {
            return;
        }
        IsoCell isoCell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        for (IsoDeadBody body : bodies) {
            // addObject preserves the existing non-(-1) ID, so the body returns under the same
            // ObjectID it had pre-warm.
            ObjectIDManager.getInstance().addObject(body);
            if (isoCell != null) {
                isoCell.addToStaticUpdaterObjectList(body);
            }
        }
    }

    private static boolean chunkBelongsToCell(IsoChunk chunk, ServerMap.ServerCell cell) {
        int cellWxBase = cell.wx * 8;
        int cellWyBase = cell.wy * 8;
        return chunk.wx >= cellWxBase
                && chunk.wx < cellWxBase + 8
                && chunk.wy >= cellWyBase
                && chunk.wy < cellWyBase + 8;
    }
}
