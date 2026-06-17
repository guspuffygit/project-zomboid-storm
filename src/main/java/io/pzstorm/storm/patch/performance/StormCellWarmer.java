package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnChunkRewarmedEvent;
import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.StormCellWarmingMetrics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import zombie.MapCollisionData;
import zombie.characters.animals.AnimalPopulationManager;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDeadBody;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.pathfind.PolygonalMap2;
import zombie.pathfind.nativeCode.PathfindNative;
import zombie.popman.ZombiePopulationManager;

/**
 * Server-side cell warming helper. Owns the warm-cell map and the disconnect/reconnect dances.
 *
 * <p>The warm path short-circuits {@code ServerCell.Unload} so the cell's {@code IsoChunk}
 * instances, grid squares, vehicles, room data, and zone bindings stay resident in memory while
 * vanilla {@code postupdate} nulls the {@code cellMap} slot and removes the cell from {@code
 * loadedCells}. When a player walks back across the boundary, {@link #rewarm(ServerMap, int, int)}
 * re-attaches the cell in place — skipping disk read, chunk binary parse, vehicle DB load, and the
 * entire border-{@code RecalcAllWithNeighbours} walk.
 *
 * <p>Gated server-side on {@link StormCellWarmingConfig#isEnabled()}. Both warm and rewarm run on
 * the main thread from {@code @Advice.OnMethodEnter} bytecode hooks; no synchronization is needed.
 */
public final class StormCellWarmer {

    private static final Map<Long, WarmCell> WARM_CELLS = new HashMap<>();

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

    public static int warmCount() {
        return WARM_CELLS.size();
    }

    /**
     * Attempt to warm {@code cell} instead of running vanilla {@code Unload}. Returns {@code true}
     * when warming succeeded — the caller's {@code @Advice.OnMethodEnter} should then skip the
     * original method body, leaving {@code postupdate} to null the {@code cellMap} slot as normal.
     * Returns {@code false} when the eligibility predicate rejected this call; the caller must fall
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

        long now = System.nanoTime();
        List<WarmAnimal> animals = new ArrayList<>();
        List<IsoDeadBody> deadBodies = new ArrayList<>();

        try {
            drainStaticUpdaterDeadBodies(cell, deadBodies);
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    IsoChunk chunk = cell.chunks[x][y];
                    if (chunk == null) {
                        continue;
                    }
                    drainAnimals(chunk, animals);
                    disconnectChunk(chunk);
                }
            }
        } catch (Throwable t) {
            StormLogger.LOGGER.error(
                    "StormCellWarmer.warm failed for cell {},{} — falling through to vanilla unload",
                    cell.wx,
                    cell.wy,
                    t);
            return false;
        }

        cell.isLoaded = false;
        WARM_CELLS.put(key(cell.wx, cell.wy), new WarmCell(cell, now, animals, deadBodies));
        StormCellWarmingMetrics.incCellsWarmed();
        StormCellWarmingMetrics.setWarmCount(WARM_CELLS.size());
        return true;
    }

    /**
     * Re-attach a warm cell at {@code (wx, wy)} into {@code serverMap} if one exists. Returns
     * {@code true} when the cell was found and rehydrated — the caller's
     * {@code @Advice.OnMethodEnter} should then skip the original {@code loadOrKeepRelevent} body.
     */
    public static boolean rewarm(ServerMap serverMap, int wx, int wy) {
        WarmCell warm = WARM_CELLS.remove(key(wx, wy));
        if (warm == null) {
            return false;
        }
        ServerMap.ServerCell cell = warm.cell;
        try {
            int x = wx - serverMap.getMinX();
            int y = wy - serverMap.getMinY();
            int width = serverMap.getMaxX() - serverMap.getMinX() + 1;
            serverMap.cellMap[y * width + x] = cell;
            if (!serverMap.loadedCells.contains(cell)) {
                serverMap.loadedCells.add(cell);
            }
            if (!serverMap.releventNow.contains(cell)) {
                serverMap.releventNow.add(cell);
            }

            for (int cx = 0; cx < 8; cx++) {
                for (int cy = 0; cy < 8; cy++) {
                    IsoChunk chunk = cell.chunks[cx][cy];
                    if (chunk == null) {
                        continue;
                    }
                    reconnectChunk(chunk);
                }
            }

            restoreAnimals(warm.animals);
            restoreDeadBodies(warm.deadBodies);

            cell.isLoaded = true;

            for (int cx = 0; cx < 8; cx++) {
                for (int cy = 0; cy < 8; cy++) {
                    IsoChunk chunk = cell.chunks[cx][cy];
                    if (chunk != null) {
                        StormEventDispatcher.dispatchEvent(new OnChunkRewarmedEvent(chunk));
                    }
                }
            }

            StormCellWarmingMetrics.incCellsRewarmed();
            StormCellWarmingMetrics.recordWarmDurationNanos(System.nanoTime() - warm.warmedAtNanos);
            StormCellWarmingMetrics.setWarmCount(WARM_CELLS.size());
            return true;
        } catch (Throwable t) {
            StormLogger.LOGGER.error(
                    "StormCellWarmer.rewarm failed for cell {},{} — leaving cell evicted",
                    wx,
                    wy,
                    t);
            StormCellWarmingMetrics.setWarmCount(WARM_CELLS.size());
            return false;
        }
    }

    /**
     * Called from a {@code ServerMap.QueuedSaveAll} entry advice. For each warm cell, flush its
     * chunks to disk by temporarily flipping {@code isLoaded} so vanilla {@code Save(false)} runs
     * (and queues per-chunk save jobs). Warm cells stay resident afterward — the rewarm path is
     * unaffected.
     */
    public static void flushSavesForAutosave() {
        if (WARM_CELLS.isEmpty()) {
            return;
        }
        for (WarmCell warm : WARM_CELLS.values()) {
            ServerMap.ServerCell cell = warm.cell;
            cell.isLoaded = true;
            try {
                cell.Save(false);
            } catch (Throwable t) {
                StormLogger.LOGGER.error(
                        "StormCellWarmer: Save failed for warm cell {},{}", cell.wx, cell.wy, t);
            } finally {
                cell.isLoaded = false;
            }
        }
    }

    /**
     * Called from a {@code ServerMap.QueuedSaveAll(quit=true)} entry advice. Destructive-unload
     * every warm cell so chunk save files reflect their final state before the JVM exits. Vanilla
     * {@code Unload} queues per-chunk {@code addSaveUnloadedJob} writes.
     */
    public static void flushAllOnShutdown() {
        if (WARM_CELLS.isEmpty()) {
            return;
        }
        for (WarmCell warm : new ArrayList<>(WARM_CELLS.values())) {
            ServerMap.ServerCell cell = warm.cell;
            cell.isLoaded = true;
            try {
                cell.Unload();
            } catch (Throwable t) {
                StormLogger.LOGGER.error(
                        "StormCellWarmer: shutdown Unload failed for warm cell {},{}",
                        cell.wx,
                        cell.wy,
                        t);
            }
        }
        WARM_CELLS.clear();
        StormCellWarmingMetrics.setWarmCount(0);
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

    private static void drainStaticUpdaterDeadBodies(
            ServerMap.ServerCell cell, List<IsoDeadBody> sink) {
        IsoCell isoCell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (isoCell == null) {
            return;
        }
        ArrayList<IsoObject> updaters = isoCell.getStaticUpdaterObjectList();
        if (updaters == null || updaters.isEmpty()) {
            return;
        }
        for (int i = updaters.size() - 1; i >= 0; i--) {
            IsoObject obj = updaters.get(i);
            if (!(obj instanceof IsoDeadBody body)) {
                continue;
            }
            IsoGridSquare sq = body.getSquare();
            if (sq == null || sq.chunk == null) {
                continue;
            }
            if (chunkBelongsToCell(sq.chunk, cell)) {
                sink.add(body);
                updaters.remove(i);
            }
        }
    }

    private static void restoreDeadBodies(List<IsoDeadBody> bodies) {
        if (bodies.isEmpty()) {
            return;
        }
        IsoCell isoCell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (isoCell == null) {
            return;
        }
        for (IsoDeadBody body : bodies) {
            isoCell.addToStaticUpdaterObjectList(body);
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
