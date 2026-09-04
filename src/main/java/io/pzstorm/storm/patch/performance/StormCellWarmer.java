package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnChunkRewarmedEvent;
import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.ChunkHydrationMetrics;
import io.pzstorm.storm.metrics.StormCellWarmingMetrics;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Vector3f;
import zombie.MapCollisionData;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.AnimalPopulationManager;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDeadBody;
import zombie.network.GameServer;
import zombie.network.ServerChunkLoader;
import zombie.network.ServerLOS;
import zombie.network.ServerMap;
import zombie.network.id.ObjectIDManager;
import zombie.pathfind.PolygonalMap2;
import zombie.pathfind.nativeCode.PathfindNative;
import zombie.popman.NetworkZombiePacker;
import zombie.popman.ZombiePopulationManager;
import zombie.vehicles.BaseVehicle;

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
 * <p>The warm set is bounded by {@link StormCellWarmingConfig#maxWarmCells()}; when exceeded, warm
 * cells are restored and destructively unloaded via the vanilla path — preferring victims no player
 * is near over the strict LRU head, at most a few per tick (see {@link #evictOverBudget(ServerMap,
 * boolean)}).
 *
 * <p>Owns {@code postupdate} while {@link #isActive()}: whenever {@link
 * StormCellWarmingConfig#isEnabled()} is on, and after it is switched off live for as long as warm
 * cells remain. In that drain mode no new cells are warmed and the whole warm set is retired
 * through {@link #evictOverBudget(ServerMap, boolean)} at its usual per-tick rate, so a live
 * disable never hands a detached cell to the vanilla loop and never spikes a tick. Single-threaded
 * — all calls happen from the server main thread.
 */
public final class StormCellWarmer {

    // Insertion order == warm order (rewarm removes, a later warm re-inserts), so the head of the
    // map is always the least-recently-warm cell — the eviction candidate when the set exceeds
    // StormCellWarmingConfig.maxWarmCells().
    private static final Map<Long, WarmCell> WARM_CELLS = new LinkedHashMap<>();

    // Identity-backed set of every animal currently inside a warmed cell. Maintained alongside
    // WARM_CELLS by drainChunk / restoreAnimals so MovingObjectSchedulerBucketAddAdvice can
    // skip a warm animal at the bucket-add chokepoint without iterating WARM_CELLS each frame.
    // Server main-thread only — no synchronization needed.
    private static final Set<IsoAnimal> WARMED_ANIMALS =
            Collections.newSetFromMap(new IdentityHashMap<>());

    // Per-tick influence set (see StormPlayerInfluenceGrid): rebuilt at the top of runPostUpdate,
    // consulted once per cell instead of sweeping every connection per cell. Valid only within the
    // tick that rebuilt it.
    private static final StormPlayerInfluenceGrid INFLUENCE_GRID = new StormPlayerInfluenceGrid();

    // releventNow.contains(cell) is a linear scan with identity equals (ServerCell overrides
    // neither equals nor hashCode); mirrored into an identity set once per tick so the per-cell
    // check is O(1) with identical semantics.
    private static final Set<ServerMap.ServerCell> RELEVENT_SET =
            Collections.newSetFromMap(new IdentityHashMap<>());

    // Eviction policy (see evictOverBudget). The strict LRU head is often a cell a player is
    // pacing right next to — evicting it is the rewarm thrash this feature exists to avoid — so
    // the victim search scans the first EVICT_SCAN_DEPTH LRU-ordered candidates for one with no
    // influence within EVICT_NEAR_MARGIN cells (Chebyshev), falling back to the plain head when
    // every candidate is near. EVICT_MAX_PER_TICK bounds the per-tick unload cost; the warm set
    // floats above the cap transiently and is trimmed over the following ticks.
    private static final int EVICT_SCAN_DEPTH = 8;
    private static final int EVICT_NEAR_MARGIN = 2;
    private static final int EVICT_MAX_PER_TICK = 4;

    // A cell that throws on rewarm would otherwise retry — and log — on every tick for as long as
    // a player stands next to it. Back the retry off linearly to a ten-second ceiling instead.
    private static final long REWARM_BACKOFF_STEP_NANOS = 1_000_000_000L;
    private static final int MAX_REWARM_BACKOFF_STEPS = 10;

    private static final int LOOKAHEAD_CELL_TILES = 64;
    private static final int LOOKAHEAD_MAX_CELLS = 2;
    private static final float LOOKAHEAD_MIN_SPEED_TILES_S = 5.0f / 3.6f;
    private static final float LOOKAHEAD_TWO_CELL_SPEED_TILES_S = 128.0f;

    // ServerCell.chunkLoader and ServerCell.startedLoading are private; the body-replaced
    // postupdate needs them to drive the save-job pump vanilla runs at its tail and to check the
    // same loading-cancellation predicate as vanilla. Bound on first use instead of in <clinit>:
    // a throwing <clinit> reaches the game thread as ExceptionInInitializerError, which is an
    // Error rather than an Exception and so routes around the Throwable-shaped guards callers
    // rely on. Lazy binding turns that same failure into "warming off" plus one log line. It also
    // keeps a client JVM that only ever calls isWarmedAnimal() from initializing ServerCell,
    // whose <clinit> starts the three ServerChunkLoader threads.
    private static ServerChunkLoader chunkLoader;
    private static Field startedLoadingField;
    private static boolean serverCellBindingFailed;

    private static boolean bindServerCellInternals() {
        if (startedLoadingField != null) {
            return true;
        }
        if (serverCellBindingFailed) {
            return false;
        }
        try {
            Field loader = ServerMap.ServerCell.class.getDeclaredField("chunkLoader");
            loader.setAccessible(true);
            chunkLoader = (ServerChunkLoader) loader.get(null);
            Field started = ServerMap.ServerCell.class.getDeclaredField("startedLoading");
            started.setAccessible(true);
            startedLoadingField = started;
            return true;
        } catch (Throwable t) {
            serverCellBindingFailed = true;
            StormLogger.LOGGER.error(
                    "StormCellWarmer could not bind ServerCell internals — cell warming disabled",
                    t);
            return false;
        }
    }

    private static boolean startedLoading(ServerMap.ServerCell cell) {
        if (startedLoadingField == null) {
            // Unknown reads as "already started": the branch that leaves vanilla's
            // loadingWasCancelled flag untouched, so an in-flight load is never mislabelled.
            return true;
        }
        try {
            return startedLoadingField.getBoolean(cell);
        } catch (IllegalAccessException e) {
            return true;
        }
    }

    private static final class WarmAnimal {
        final IsoAnimal animal;
        final int x;
        final int y;
        final int z;

        WarmAnimal(IsoAnimal animal, IsoGridSquare originalSquare) {
            this.animal = animal;
            this.x = originalSquare.getX();
            this.y = originalSquare.getY();
            this.z = originalSquare.getZ();
        }
    }

    private static final class WarmCell {
        final ServerMap.ServerCell cell;
        final long warmedAtNanos;
        final List<WarmAnimal> animals;
        final List<IsoDeadBody> deadBodies;
        int rewarmFailures;
        long retryNotBeforeNanos;
        int reconnectCursor;

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
     * Whether the warmer must own {@code ServerMap.postupdate} this tick: warming is enabled, or it
     * was disabled live and warm cells are still being drained. The budgeted and vanilla postupdate
     * bodies must not run while this is true — they would tick or destructively unload cells whose
     * world-system bindings are detached.
     */
    public static boolean isActive() {
        return ownsPostUpdate(StormCellWarmingConfig.isEnabled(), WARM_CELLS.size());
    }

    /** Pure form of {@link #isActive()}; package-private for tests. */
    static boolean ownsPostUpdate(boolean enabled, int warmCount) {
        return enabled || warmCount > 0;
    }

    /**
     * Cap the eviction pass enforces this tick: the configured cap while enabled, {@code 0} while
     * draining (retire everything), or {@code -1} for "no eviction pass" when enabled and
     * unbounded. Package-private for tests.
     */
    static int effectiveEvictionCap(boolean enabled, int configuredMax) {
        if (!enabled) {
            return 0;
        }
        return configuredMax <= 0 ? -1 : configuredMax;
    }

    /**
     * Fast O(1) test used by {@code MovingObjectSchedulerBucketAddAdvice} to skip warm animals at
     * the bucket-add chokepoint. Returns {@code false} for non-animals and for any animal that
     * isn't currently inside a warmed cell.
     */
    public static boolean isWarmedAnimal(Object obj) {
        return obj instanceof IsoAnimal animal && WARMED_ANIMALS.contains(animal);
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
     * body while {@link #isActive()}. With warming switched off (drain mode) the first branch still
     * rewarms cells that became relevant, the second never warms, and the eviction pass retires
     * whatever is left.
     */
    public static void runPostUpdate(ServerMap serverMap) {
        bindServerCellInternals();
        boolean draining = !StormCellWarmingConfig.isEnabled();
        boolean pathfindPaused = false;
        long cancelledQueued = 0;
        long cancelledInFlight = 0;
        ArrayList<ServerMap.ServerCell> loadedCells = serverMap.loadedCells;
        ArrayList<ServerMap.ServerCell> releventNow = serverMap.releventNow;
        try {
            projectDriverLookahead(serverMap);
            RELEVENT_SET.clear();
            RELEVENT_SET.addAll(releventNow);
            INFLUENCE_GRID.rebuild(
                    GameServer.udpEngine == null
                            ? Collections.emptyList()
                            : GameServer.udpEngine.connections);
            for (int n = 0; n < loadedCells.size(); n++) {
                ServerMap.ServerCell cell = loadedCells.get(n);
                boolean shouldBeLoaded =
                        RELEVENT_SET.contains(cell)
                                || INFLUENCE_GRID.containsCell(cell.wx, cell.wy);
                boolean warm = isWarm(cell);

                if (warm) {
                    if (shouldBeLoaded) {
                        // Only update on successful rewarm — a failed rewarm leaves the cell in
                        // the warm map with chunks possibly half-attached, and update() on that
                        // state would tick disconnected chunks.
                        if (rewarm(cell)) {
                            cell.update();
                        }
                    }
                    // else: stay warm — skip both Unload and update.
                    continue;
                }

                if (!cell.isLoaded) {
                    if (!shouldBeLoaded && !cell.cancelLoading) {
                        if (!startedLoading(cell)) {
                            cell.loadingWasCancelled = true;
                            cancelledQueued++;
                        } else {
                            cancelledInFlight++;
                        }
                        cell.cancelLoading = true;
                    }
                } else if (!shouldBeLoaded) {
                    if (!draining && warm(cell)) {
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
            pathfindPaused = evictOverBudget(serverMap, pathfindPaused);
            if (draining && WARM_CELLS.isEmpty()) {
                StormLogger.LOGGER.info(
                        "Cell warming drain complete — ServerMap.postupdate returns to vanilla");
            }
            // Inside the guard: this is Storm instrumentation, and a metric class whose own
            // <clinit> fails would otherwise throw an Error straight into the tick.
            ChunkHydrationMetrics.recordCancelledCells(cancelledQueued, cancelledInFlight);
        } catch (Throwable t) {
            StormLogger.LOGGER.error("StormCellWarmer.runPostUpdate failed", t);
        } finally {
            if (pathfindPaused) {
                ServerLOS.instance.resume();
            }
        }

        NetworkZombiePacker.getInstance().postupdate();
        if (chunkLoader != null) {
            chunkLoader.updateSaved();
        }
    }

    private static void projectDriverLookahead(ServerMap serverMap) {
        ArrayList<IsoPlayer> players = GameServer.Players;
        if (players == null) {
            return;
        }
        for (int i = 0; i < players.size(); i++) {
            try {
                IsoPlayer player = players.get(i);
                if (player == null) {
                    continue;
                }
                BaseVehicle vehicle = player.getVehicle();
                if (vehicle == null) {
                    continue;
                }
                Vector3f v = new Vector3f();
                vehicle.getLinearVelocity(v);
                float vx = v.x;
                float vy = v.z;
                if (Float.isNaN(vx) || Float.isNaN(vy)) {
                    continue;
                }
                float speed = (float) Math.sqrt(vx * vx + vy * vy);
                if (speed < LOOKAHEAD_MIN_SPEED_TILES_S) {
                    continue;
                }
                float startX = player.getX();
                float startY = player.getY();
                if (Float.isNaN(startX) || Float.isNaN(startY)) {
                    continue;
                }
                int steps = speed >= LOOKAHEAD_TWO_CELL_SPEED_TILES_S ? LOOKAHEAD_MAX_CELLS : 1;
                float nx = vx / speed;
                float ny = vy / speed;
                // Cap at LOOKAHEAD_MAX_CELLS: each projected cell keeps full chunk state resident.
                for (int step = 1; step <= steps; step++) {
                    float px = startX + nx * LOOKAHEAD_CELL_TILES * step;
                    float py = startY + ny * LOOKAHEAD_CELL_TILES * step;
                    int wx = (int) Math.floor(px / LOOKAHEAD_CELL_TILES);
                    int wy = (int) Math.floor(py / LOOKAHEAD_CELL_TILES);
                    int lx = wx - serverMap.getMinX();
                    int ly = wy - serverMap.getMinY();
                    if (!serverMap.isInvalidCell(lx, ly)) {
                        serverMap.loadOrKeepRelevent(lx, ly);
                    }
                }
            } catch (Throwable t) {
            }
        }
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
        IsoCell isoCell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        int disconnectedX = -1, disconnectedY = -1;

        try {
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    IsoChunk chunk = cell.chunks[x][y];
                    if (chunk == null) {
                        continue;
                    }
                    drainChunk(chunk, isoCell, animals, deadBodies);
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
     * here. Returns {@code false} only on internal error, or while a previous error is backing off
     * — the cell stays warm either way so it is never dropped, only retried less often.
     */
    public static boolean rewarm(ServerMap.ServerCell cell) {
        long cellKey = key(cell.wx, cell.wy);
        WarmCell warm = WARM_CELLS.get(cellKey);
        if (warm == null) {
            return false;
        }
        long opStart = System.nanoTime();
        if (warm.rewarmFailures > 0 && opStart - warm.retryNotBeforeNanos < 0) {
            return false;
        }
        try {
            reconnectAndRestore(cell, warm);
        } catch (Throwable t) {
            warm.rewarmFailures++;
            warm.retryNotBeforeNanos =
                    opStart
                            + Math.min(warm.rewarmFailures, MAX_REWARM_BACKOFF_STEPS)
                                    * REWARM_BACKOFF_STEP_NANOS;
            StormLogger.LOGGER.error(
                    "StormCellWarmer.rewarm failed for cell {},{} (attempt {}) — leaving in warm"
                            + " state",
                    cell.wx,
                    cell.wy,
                    warm.rewarmFailures,
                    t);
            return false;
        }
        // Removed only once the chunks are actually re-attached. Re-inserting a failed cell would
        // move it to the tail of the insertion-ordered map, making the one cell that can't rewarm
        // look permanently freshest and pushing healthy cells out of the warm budget ahead of it.
        WARM_CELLS.remove(cellKey);

        try {
            for (int cx = 0; cx < 8; cx++) {
                for (int cy = 0; cy < 8; cy++) {
                    IsoChunk chunk = cell.chunks[cx][cy];
                    if (chunk != null) {
                        StormEventDispatcher.dispatchEvent(new OnChunkRewarmedEvent(chunk));
                    }
                }
            }
        } catch (Throwable t) {
            StormLogger.LOGGER.error(
                    "StormCellWarmer OnChunkRewarmedEvent dispatch failed for cell {},{}",
                    cell.wx,
                    cell.wy,
                    t);
        }

        long opEnd = System.nanoTime();
        StormCellWarmingMetrics.incCellsRewarmed();
        StormCellWarmingMetrics.recordWarmDurationNanos(opEnd - warm.warmedAtNanos);
        StormCellWarmingMetrics.recordRewarmOpNanos(opEnd - opStart);
        StormCellWarmingMetrics.setWarmCount(WARM_CELLS.size());
        return true;
    }

    private static void reconnectAndRestore(ServerMap.ServerCell cell, WarmCell warm) {
        // Cursor rather than a fresh 8x8 sweep, because rewarm retries after a failure and
        // addChunkToWorld is not idempotent: replaying the chunks that already succeeded would
        // double-add them to collision, pathfind and both population managers. A throw leaves the
        // cursor on the chunk that failed, so the retry resumes at exactly that one.
        while (warm.reconnectCursor < 64) {
            IsoChunk chunk = cell.chunks[warm.reconnectCursor / 8][warm.reconnectCursor % 8];
            if (chunk != null) {
                reconnectChunk(chunk);
            }
            warm.reconnectCursor++;
        }
        restoreAnimals(warm.animals);
        restoreDeadBodies(warm.deadBodies);
    }

    /**
     * Eviction-only variant of {@link #reconnectAndRestore}: these chunks go straight into {@code
     * cell.Unload()}, so only the state that unload's own teardown consumes is re-attached.
     *
     * <ul>
     *   <li>The animal population manager and the pathfinder are re-added per chunk — unload's
     *       {@code removeChunkFromWorld} calls must stay balanced against an add (the native
     *       pathfind side tracks loaded chunks, and the animal manager virtualizes a chunk's
     *       population on remove).
     *   <li>{@code ZombiePopulationManager.addChunkToWorld} is skipped. The native side of an
     *       unbalanced zombie remove is a harmless bitmask clear ({@code Cell::setChunkLoaded}
     *       returns before any cell touch), and the Java side only virtualizes real zombies on the
     *       chunk's squares — a warmed cell has none, {@code warm()} virtualized them. The re-add,
     *       by contrast, is actively destructive: {@code n_loadChunk(add)} reloads the cold popman
     *       cell into the native worker for ~2s (cell touch + zpop load) with no Java chunks or
     *       pathfind data behind it. In that window the native respawn check ({@code
     *       tryRepopChunk}) runs every worker tick on exactly the cells whose respawn clocks have
     *       aged into eligibility — eviction victims are the least-recently-visited cells — and
     *       each doomed attempt burns a chunk's 16-game-hour repop cooldown before the spawn task
     *       is discarded; once every eligible chunk is burned, the empty-eligible path restamps the
     *       cell's 16-hour clock with zero zombies spawned. At production eviction rates this
     *       starved respawn across the whole map. It also round-trips the chunk's virtual zombies
     *       through the main thread (realize into a dying cell, bounce, re-virtualize) for nothing.
     *   <li>{@code MapCollisionData.addChunkToWorld} is skipped: its {@code removeChunkFromWorld}
     *       is an empty no-op (vanilla), so the chunk never left collision during {@code warm()}
     *       and the re-add would be pure wasted native work on a chunk that is leaving.
     *   <li>Stashed animals are handed straight to {@code virtualizeAnimal} — vanilla's own
     *       transition for an animal leaving the loaded world (its only vanilla caller is the
     *       wander-out path in {@code IsoMovingObject.doDeferredMovement}). Relative to the full
     *       path's re-place-then-unload, it additionally frees the {@code AnimalInstanceManager}
     *       entry (which vanilla's server-side chunk unload leaks — its remove is client-gated) and
     *       broadcasts the id through {@code AnimalSynchronizationManager.delete}, so a client
     *       still near the evicted cell drops the instance instead of keeping a frozen ghost.
     *   <li>Dead bodies are fully restored ({@link #restoreDeadBodies}) so unload's save path
     *       serializes them under the ObjectIDs vanilla knows them by.
     * </ul>
     *
     * Advances the shared {@code reconnectCursor} chunk by chunk like the full path, so a throw
     * mid-sweep leaves an accurate record of what was re-added (the eviction caller unloads anyway,
     * but never double-adds).
     */
    private static void evictLiteReconnect(ServerMap.ServerCell cell, WarmCell warm) {
        while (warm.reconnectCursor < 64) {
            IsoChunk chunk = cell.chunks[warm.reconnectCursor / 8][warm.reconnectCursor % 8];
            if (chunk != null && chunk.jobType != IsoChunk.JobType.SoftReset) {
                AnimalPopulationManager.getInstance().addChunkToWorld(chunk);
                if (PathfindNative.useNativeCode) {
                    PathfindNative.instance.addChunkToWorld(chunk);
                } else {
                    PolygonalMap2.instance.addChunkToWorld(chunk);
                }
            }
            warm.reconnectCursor++;
        }
        virtualizeAnimals(warm.animals);
        restoreDeadBodies(warm.deadBodies);
    }

    /**
     * Hands every stashed animal to the population manager, the outcome a vanilla unload of its
     * chunk would have produced (same terminal state as {@link #restoreAnimals}'s square-gone
     * fallback). The animal is never serialized into the evicted chunk's save in either eviction
     * path — {@code IsoChunk.removeFromWorld} clears every square's moving-object list before
     * {@code ServerCell.Unload} queues the save job — so the native population record is the sole
     * owner afterward. Clears the list so a retry can never virtualize the same animal twice.
     */
    private static void virtualizeAnimals(List<WarmAnimal> animals) {
        for (WarmAnimal stash : animals) {
            IsoAnimal animal = stash.animal;
            WARMED_ANIMALS.remove(animal);
            try {
                AnimalPopulationManager.getInstance().virtualizeAnimal(animal);
            } catch (Throwable t) {
                StormLogger.LOGGER.error(
                        "StormCellWarmer failed to virtualize animal at {},{},{}",
                        stash.x,
                        stash.y,
                        stash.z,
                        t);
            }
        }
        animals.clear();
    }

    /**
     * Memory bound on the warm set. A warm cell keeps its full chunk/square state resident, so
     * without a cap the map grows with every cell any player has ever walked away from. Evicts warm
     * cells above {@link StormCellWarmingConfig#maxWarmCells()} (above zero while draining after a
     * live disable, see {@link #effectiveEvictionCap}) through the vanilla destructive path:
     * reconnect first, so the pop managers virtualize animals and the chunk save jobs persist state
     * exactly as a vanilla Unload of a live cell would — via {@link #evictLiteReconnect}, which
     * skips the re-attach work the immediate unload would only undo.
     *
     * <p>Victim choice is distance-aware (the influence grid is rebuilt every tick): the LRU head
     * is often a cell a player is standing two cells away from — the very cell most likely to be
     * rewarmed moments after eviction — so the first {@value #EVICT_SCAN_DEPTH} LRU-ordered
     * candidates are scanned for one with no player influence within {@value #EVICT_NEAR_MARGIN}
     * cells, falling back to the plain head when every candidate is near (which also guarantees a
     * cell whose rewarm keeps throwing is eventually retired). At most {@value #EVICT_MAX_PER_TICK}
     * cells are evicted per tick — each eviction is a full vanilla unload, so the cap keeps a warm
     * burst from turning into one spike tick; the set floats above the cap transiently ({@code
     * storm_cell_warm_over_cap}).
     *
     * <p>Everything still in {@code WARM_CELLS} at this point was either not relevant this tick or
     * failed to rewarm (relevant cells that rewarmed were removed by the caller's loop), so
     * evicting any of them is always safe.
     *
     * @return updated pathfindPaused flag — caller's finally block resumes ServerLOS.
     */
    private static boolean evictOverBudget(ServerMap serverMap, boolean pathfindPaused) {
        int max =
                effectiveEvictionCap(
                        StormCellWarmingConfig.isEnabled(), StormCellWarmingConfig.maxWarmCells());
        if (max < 0) {
            return pathfindPaused;
        }
        boolean evicted = false;
        int evictions = 0;
        long nearSkips = 0;
        while (WARM_CELLS.size() > max && evictions < EVICT_MAX_PER_TICK) {
            int depth = Math.min(EVICT_SCAN_DEPTH, WARM_CELLS.size());
            int[] wxs = new int[depth];
            int[] wys = new int[depth];
            Iterator<WarmCell> scan = WARM_CELLS.values().iterator();
            for (int i = 0; i < depth; i++) {
                WarmCell candidate = scan.next();
                wxs[i] = candidate.cell.wx;
                wys[i] = candidate.cell.wy;
            }
            int victimIndex = selectEvictionVictim(wxs, wys, INFLUENCE_GRID, EVICT_NEAR_MARGIN);
            nearSkips += victimIndex;
            Iterator<WarmCell> it = WARM_CELLS.values().iterator();
            for (int i = 0; i < victimIndex; i++) {
                it.next();
            }
            WarmCell oldest = it.next();
            it.remove();
            evictions++;
            ServerMap.ServerCell cell = oldest.cell;
            try {
                // No OnChunkRewarmedEvent here — these chunks are leaving the world, not
                // re-entering the active set; mods must not be told they are live again.
                evictLiteReconnect(cell, oldest);
            } catch (Throwable t) {
                StormLogger.LOGGER.error(
                        "StormCellWarmer eviction restore failed for cell {},{} — unloading anyway",
                        cell.wx,
                        cell.wy,
                        t);
            }
            if (!pathfindPaused) {
                ServerLOS.instance.suspend();
                pathfindPaused = true;
            }
            int x = cell.wx - serverMap.getMinX();
            int y = cell.wy - serverMap.getMinY();
            int width = serverMap.getMaxX() - serverMap.getMinX() + 1;
            cell.Unload();
            serverMap.cellMap[y * width + x] = null;
            serverMap.loadedCells.remove(cell);
            evicted = true;
            StormCellWarmingMetrics.incCellsEvicted();
            StormCellWarmingMetrics.recordWarmDurationNanos(
                    System.nanoTime() - oldest.warmedAtNanos);
        }
        if (evicted) {
            StormCellWarmingMetrics.setWarmCount(WARM_CELLS.size());
        }
        if (nearSkips > 0) {
            StormCellWarmingMetrics.incEvictNearSkips(nearSkips);
        }
        StormCellWarmingMetrics.setWarmOverCap(Math.max(0, WARM_CELLS.size() - max));
        return pathfindPaused;
    }

    /**
     * Index of the eviction victim among LRU-ordered candidate cell coordinates: the first with no
     * player influence within {@code margin} cells (Chebyshev), else {@code 0} — the plain LRU
     * head. Package-private for tests; the grid must have been rebuilt this tick.
     */
    static int selectEvictionVictim(
            int[] candidateWx, int[] candidateWy, StormPlayerInfluenceGrid grid, int margin) {
        for (int i = 0; i < candidateWx.length; i++) {
            if (!grid.nearInfluence(candidateWx[i], candidateWy[i], margin)) {
                return i;
            }
        }
        return 0;
    }

    private static String ineligibleReason(ServerMap.ServerCell cell) {
        if (!bindServerCellInternals()) {
            // Without the save-job pump a warm cell's chunk saves would never be drained.
            return "server_cell_binding";
        }
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

    // Drains a chunk's dynamic state in a single square walk. Animals are stashed so they stop
    // ticking while warm. Dead bodies leave the ObjectIDType.DeadBody registry (so
    // IsoDeadBody.updateBodies() stops advancing rot stages and can't auto-remove them while warm)
    // and the staticUpdaterObjectList (so per-tick updaters skip them); their ObjectID stays on the
    // body, so ObjectIDManager.addObject(body) in restoreDeadBodies re-registers under the same ID
    // and network sync stays valid. Walking the cell's own squares keeps the cost proportional to
    // the cell — scanning ObjectIDType.DeadBody.getObjects() instead would cost O(every corpse on
    // the server) for each cell warmed.
    private static void drainChunk(
            IsoChunk chunk, IsoCell isoCell, List<WarmAnimal> animals, List<IsoDeadBody> bodies) {
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
                        animals.add(new WarmAnimal(animal, sq));
                        WARMED_ANIMALS.add(animal);
                    }
                }
                ArrayList<IsoMovingObject> statics = sq.getStaticMovingObjects();
                for (int m = 0; m < statics.size(); m++) {
                    if (statics.get(m) instanceof IsoDeadBody body && isRegisteredBody(body, sq)) {
                        bodies.add(body);
                        ObjectIDManager.getInstance().remove(body.getObjectID());
                        if (isoCell != null) {
                            isoCell.removeFromStaticUpdaterObjectList(body);
                        }
                    }
                }
            }
        }
    }

    // A body only counts as drainable if the registry still maps its ID back to it and the square
    // it is listed on is the square it claims: IsoDeadBody.updateBodies() unregisters expired
    // corpses without unlisting them, and restoring one of those would hand it a freshly allocated
    // ObjectID that vanilla never gave it.
    private static boolean isRegisteredBody(IsoDeadBody body, IsoGridSquare sq) {
        return body.getSquare() == sq && ObjectIDManager.get(body.getObjectID()) == body;
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
        IsoCell isoCell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        for (WarmAnimal stash : animals) {
            IsoAnimal animal = stash.animal;
            // Clear the warm marker unconditionally — once we've decided to restore, the animal
            // must tick on the next frame even if reattaching to its original square fails.
            WARMED_ANIMALS.remove(animal);
            // Per-animal guard: one animal that can't be placed must not abandon the animals and
            // dead bodies queued behind it, which would leave them stranded in WARMED_ANIMALS or
            // unregistered for the rest of the session.
            try {
                IsoGridSquare sq =
                        isoCell == null ? null : isoCell.getGridSquare(stash.x, stash.y, stash.z);
                if (sq != null) {
                    animal.setMovingSquare(sq);
                    animal.updateLastTimeSinceUpdate();
                    continue;
                }
                // The square is gone (chunk replaced, or its z-level dropped) and drainChunk
                // already unlinked the animal, so there is nothing left holding it: no square to
                // serialize it and no population entry to respawn it. Hand it to the population
                // manager, which is what a vanilla unload of this chunk would have done.
                StormLogger.LOGGER.warn(
                        "StormCellWarmer: square {},{},{} is gone — virtualizing animal instead of"
                                + " restoring it",
                        stash.x,
                        stash.y,
                        stash.z);
                AnimalPopulationManager.getInstance().virtualizeAnimal(animal);
            } catch (Throwable t) {
                StormLogger.LOGGER.error(
                        "StormCellWarmer failed to restore animal at {},{},{}",
                        stash.x,
                        stash.y,
                        stash.z,
                        t);
            }
        }
        // Every entry has now been placed, virtualized or logged. Dropping them keeps a retried
        // rewarm from virtualizing the same animal twice, which would duplicate it in the
        // population manager.
        animals.clear();
    }

    private static void restoreDeadBodies(List<IsoDeadBody> bodies) {
        if (bodies.isEmpty()) {
            return;
        }
        IsoCell isoCell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        // Drained as it goes, for the same reason restoreAnimals clears: rewarm can fail and be
        // retried, and re-running this list from the top would add an already-restored body to the
        // static updater list a second time.
        Iterator<IsoDeadBody> it = bodies.iterator();
        while (it.hasNext()) {
            IsoDeadBody body = it.next();
            it.remove();
            try {
                // addObject preserves the existing non-(-1) ID, so the body returns under the same
                // ObjectID it had pre-warm.
                ObjectIDManager.getInstance().addObject(body);
                if (isoCell != null) {
                    isoCell.addToStaticUpdaterObjectList(body);
                }
            } catch (Throwable t) {
                StormLogger.LOGGER.error("StormCellWarmer failed to restore a dead body", t);
            }
        }
    }
}
