package io.pzstorm.storm.los;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.StormServerLosMetrics;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import zombie.characters.IsoPlayer;
import zombie.characters.VisibilityData;
import zombie.core.math.PZMath;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.LosUtil;
import zombie.iso.areas.IsoRoom;
import zombie.network.ServerMap;

/**
 * Slot-parameterized re-implementation of {@code ServerLOS$LOSThread.calcLOS} plus the parallel
 * dispatcher that drives several of them at once.
 *
 * <p>Vanilla {@code calcLOS} hardcodes scratch slot {@code 0} everywhere ({@code
 * LosUtil.cachedresults[0]}, {@code IsoPlayer.players[0]}, {@code sq.CalcVisibility(0,...)}, {@code
 * sq.isCouldSee(0)}, {@code sq.checkRoomSeen(0)}). {@link #calcLOS(Object, int)} threads an
 * explicit {@code slot} through the whole scan so that, with each worker bound to a distinct slot,
 * per-player scans run independently.
 *
 * <p><b>Phase 1 (parity):</b> {@link #calcLOS(Object, int)} run single-threaded with {@code slot ==
 * 0} produces a byte-identical {@code visible} grid to vanilla (proven by the eval parity harness).
 *
 * <p><b>Phase 2 (parallel):</b> {@link #runInnerParallel(Object)} always replaces the body of
 * vanilla {@code runInner}: it reproduces vanilla's snapshot / status state machine / park-wait
 * protocol exactly, but fans the per-player {@code calcLOS} calls out across up to {@link
 * StormServerLosConfig#MAX} workers (the LOS thread itself is slot 0; helper threads are slots
 * 1..K-1). At {@code threads == 1} the batch runs single-threaded on slot 0 — byte-identical to
 * vanilla for the {@code visible} grid, with the onSee lock and helper pool never engaged — so its
 * {@code storm_serverlos_*} timings form a like-for-like baseline against the parallel runs.
 *
 * <p>Per-slot state ({@code cachedresults[slot]}, {@code lighting[slot]}) is disjoint across
 * workers. The remaining shared-state hazards are handled by the server-only structural patches:
 * {@code IsoGridSquareLosParallelPatch} (lazy {@code lighting[slot]} alloc + per-thread {@code
 * tempo}/{@code tempo2}) and {@code IsoRoomOnSeePatch} (serialized {@code onSee} via {@link
 * #lockOnSee()}). {@code LosUtil.lineClearCached} is already per-slot safe.
 *
 * <p><b>Differences from vanilla, all parity-neutral for the {@code visible} grid:</b>
 *
 * <ul>
 *   <li>{@code IsoPlayer.players[slot]} is never written; {@link #checkRoomSeen(IsoGridSquare,
 *       IsoPlayer)} takes the player explicitly. (The {@code visible} grid does not depend on
 *       room-seen state.)
 *   <li>The {@code isLocalPlayer()} music-intensity event is dropped — dedicated-server players are
 *       never local.
 * </ul>
 */
public final class StormServerLos {

    private static final String SERVER_LOS_CLASS = "zombie.network.ServerLOS";
    private static final String LOS_THREAD_CLASS = "zombie.network.ServerLOS$LOSThread";
    private static final String PLAYER_DATA_CLASS = "zombie.network.ServerLOS$PlayerData";
    private static final String UPDATE_STATUS_CLASS = "zombie.network.ServerLOS$UpdateStatus";

    private static volatile boolean initialized;

    // PlayerData fields (Phase 1).
    private static Field fPlayer;
    private static Field fPx;
    private static Field fPy;
    private static Field fPz;
    private static Field fVisible;

    // Phase-2 dispatch handles.
    private static Field fInstance; // ServerLOS.instance (static)
    private static Field fPlayersMain; // ServerLOS.playersMain
    private static Field fPlayersLos; // ServerLOS.playersLos
    private static Field fMapLoading; // ServerLOS.mapLoading
    private static Field fSuspended; // ServerLOS.suspended
    private static Field fNotifier; // ServerLOS$LOSThread.notifier
    private static Field fStatus; // ServerLOS$PlayerData.status
    private static Object stWaiting; // UpdateStatus.WaitingInLOS
    private static Object stBusy; // UpdateStatus.BusyInLOS
    private static Object stReady; // UpdateStatus.ReadyInLOS

    /**
     * Persistent helper pool, sized for the worst case (MAX-1 helpers). Slot 0 = the LOS thread.
     */
    private static volatile ExecutorService losPool;

    /** Serializes {@code IsoRoom.onSee} across workers (only taken when {@code threads >= 2}). */
    private static final ReentrantLock ON_SEE_LOCK = new ReentrantLock();

    private StormServerLos() {}

    // ------------------------------------------------------------------------------------------
    // Parallel dispatcher
    // ------------------------------------------------------------------------------------------

    /**
     * Drop-in replacement for {@code ServerLOS$LOSThread.runInner}; always handles the tick and
     * returns {@code true} so the caller skips the vanilla body. At {@code threads == 1} it runs
     * the batch single-threaded on slot 0 (byte-identical {@code visible} grid to vanilla, with the
     * onSee lock and helper pool never engaged) and still records {@code storm_serverlos_*}, so the
     * single-threaded run is a like-for-like baseline for the parallel runs. At {@code threads >=
     * 2} it fans the per-player scans across that many slots.
     *
     * @param losThread the {@code ServerLOS$LOSThread} instance ({@code @Advice.This}).
     */
    public static boolean runInnerParallel(Object losThread) {
        int k = StormServerLosConfig.threads();
        ensureInit();
        try {
            Object serverLos = fInstance.get(null);
            @SuppressWarnings("unchecked")
            ArrayList<Object> playersMain = (ArrayList<Object>) fPlayersMain.get(serverLos);
            @SuppressWarnings("unchecked")
            ArrayList<Object> playersLos = (ArrayList<Object>) fPlayersLos.get(serverLos);
            Object notifier = fNotifier.get(losThread);

            synchronized (playersMain) {
                playersLos.clear();
                playersLos.addAll(playersMain);
            }

            // Collect the WaitingInLOS players, claim them (BusyInLOS), honour the map-loading
            // break.
            ArrayList<Object> batch = new ArrayList<>();
            for (int i = 0; i < playersLos.size(); i++) {
                Object pd = playersLos.get(i);
                if (fStatus.get(pd) == stWaiting) {
                    fStatus.set(pd, stBusy);
                    batch.add(pd);
                }
                if (fMapLoading.getBoolean(serverLos)) {
                    break;
                }
            }

            long t0 = System.nanoTime();
            runBatch(batch, k);
            StormServerLosMetrics.recordTick(System.nanoTime() - t0, batch.size());

            // Vanilla park/wait protocol.
            while (shouldWait(serverLos, playersLos, playersMain)) {
                fSuspended.setBoolean(serverLos, true);
                synchronized (notifier) {
                    try {
                        notifier.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            fSuspended.setBoolean(serverLos, false);
            return true;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "StormServerLos.runInnerParallel reflective access failed", e);
        }
    }

    /**
     * Runs every player in {@code batch}, fanning slices across {@code k} slots (slot 0 inline).
     */
    private static void runBatch(ArrayList<Object> batch, int k) {
        int n = batch.size();
        if (n == 0) {
            return;
        }
        int workers = Math.min(k, n);
        if (workers <= 1) {
            for (int i = 0; i < n; i++) {
                processPlayer(batch.get(i), 0);
            }
            return;
        }

        // Contiguous slice boundaries; slice s -> slot s.
        int[] bounds = new int[workers + 1];
        int base = n / workers;
        int rem = n % workers;
        for (int s = 0; s < workers; s++) {
            bounds[s + 1] = bounds[s] + base + (s < rem ? 1 : 0);
        }

        ExecutorService pool = pool();
        Future<?>[] futures = new Future<?>[workers - 1];
        for (int s = 1; s < workers; s++) {
            final int slot = s;
            final int lo = bounds[s];
            final int hi = bounds[s + 1];
            futures[s - 1] =
                    pool.submit(
                            () -> {
                                for (int i = lo; i < hi; i++) {
                                    processPlayer(batch.get(i), slot);
                                }
                            });
        }

        // Slot 0 runs inline on the LOS thread.
        for (int i = bounds[0]; i < bounds[1]; i++) {
            processPlayer(batch.get(i), 0);
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                throw new IllegalStateException("StormServerLos parallel LOS worker failed", e);
            }
        }
    }

    private static void processPlayer(Object playerData, int slot) {
        long t0 = System.nanoTime();
        calcLOS(playerData, slot);
        StormServerLosMetrics.recordCalcLos(System.nanoTime() - t0, slot);
        try {
            fStatus.set(playerData, stReady);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("StormServerLos.processPlayer status write failed", e);
        }
    }

    /** Reimplementation of vanilla {@code LOSThread.shouldWait}. */
    private static boolean shouldWait(
            Object serverLos, ArrayList<Object> playersLos, ArrayList<Object> playersMain)
            throws IllegalAccessException {
        if (fMapLoading.getBoolean(serverLos)) {
            return true;
        }
        for (int i = 0; i < playersLos.size(); i++) {
            if (fStatus.get(playersLos.get(i)) == stWaiting) {
                return false;
            }
        }
        synchronized (playersMain) {
            return playersLos.size() == playersMain.size();
        }
    }

    private static ExecutorService pool() {
        ExecutorService p = losPool;
        if (p == null) {
            synchronized (StormServerLos.class) {
                p = losPool;
                if (p == null) {
                    AtomicInteger seq = new AtomicInteger(1);
                    p =
                            Executors.newFixedThreadPool(
                                    StormServerLosConfig.MAX - 1,
                                    r -> {
                                        Thread t =
                                                new Thread(
                                                        r, "LOS-Worker-" + seq.getAndIncrement());
                                        t.setDaemon(true);
                                        return t;
                                    });
                    losPool = p;
                    LOGGER.info(
                            "StormServerLos: started {} LOS helper threads",
                            StormServerLosConfig.MAX - 1);
                }
            }
        }
        return p;
    }

    // ------------------------------------------------------------------------------------------
    // onSee serialization (used by IsoRoomOnSeeAdvice)
    // ------------------------------------------------------------------------------------------

    /** Acquires the onSee lock when running parallel; returns whether it was taken. */
    public static boolean lockOnSee() {
        if (StormServerLosConfig.threads() < 2) {
            return false;
        }
        ON_SEE_LOCK.lock();
        StormServerLosMetrics.recordOnSeeLocked();
        return true;
    }

    public static void unlockOnSee() {
        ON_SEE_LOCK.unlock();
    }

    // ------------------------------------------------------------------------------------------
    // Per-player scan (Phase 1 — parity-proven)
    // ------------------------------------------------------------------------------------------

    /**
     * Runs one player's LOS grid scan into {@code playerData.visible} using the given scratch
     * {@code slot}. Faithful re-creation of vanilla {@code calcLOS}, including the
     * unchanged-position skip fast-path.
     *
     * @param playerData a vanilla {@code ServerLOS$PlayerData} instance (passed as {@code Object}
     *     because the type is package-private).
     * @param slot scratch slot in {@code 0..3}; selects {@code LosUtil.cachedresults[slot]} and the
     *     per-square {@code lighting[slot]}.
     */
    public static void calcLOS(Object playerData, int slot) {
        ensureInit();
        try {
            IsoPlayer player = (IsoPlayer) fPlayer.get(playerData);

            int oldPx = fPx.getInt(playerData);
            int oldPy = fPy.getInt(playerData);
            int oldPz = fPz.getInt(playerData);

            int px = PZMath.fastfloor(player.getX());
            int py = PZMath.fastfloor(player.getY());
            int pz = PZMath.fastfloor(player.getZ());
            boolean skip = oldPx == px && oldPy == py && oldPz == pz;

            fPx.setInt(playerData, px);
            fPy.setInt(playerData, py);
            fPz.setInt(playerData, pz);

            player.initLightInfo2();
            if (skip) {
                return;
            }

            LosUtil.PerPlayerData ppd = LosUtil.cachedresults[slot];
            ppd.checkSize();
            byte[][][] cache = ppd.cachedresults;
            for (int x = 0; x < LosUtil.sizeX; x++) {
                for (int y = 0; y < LosUtil.sizeY; y++) {
                    for (int z = 0; z < LosUtil.sizeZ; z++) {
                        cache[x][y][z] = 0;
                    }
                }
            }

            boolean[][][] visible = (boolean[][][]) fVisible.get(playerData);
            int minX = px - 48;
            int maxX = minX + 96;
            int minY = py - 48;
            int maxY = minY + 96;
            int minZ = pz - LosUtil.sizeZ / 2;
            int maxZ = minZ + LosUtil.sizeZ;
            VisibilityData visibilityData = player.calculateVisibilityData();

            for (int x = minX; x < maxX; x++) {
                for (int y = minY; y < maxY; y++) {
                    for (int z = minZ; z < maxZ; z++) {
                        IsoGridSquare sq = ServerMap.instance.getGridSquare(x, y, z);
                        if (sq != null) {
                            sq.CalcVisibility(slot, player, visibilityData);
                            visible[x - minX][y - minY][z - minZ] = sq.isCouldSee(slot);
                            checkRoomSeen(sq, player);
                        } else {
                            visible[x - minX][y - minY][z - minZ] = false;
                        }
                    }
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("StormServerLos.calcLOS reflective access failed", e);
        }
    }

    /**
     * Re-implementation of {@code IsoGridSquare.checkRoomSeen(int)} that takes the player
     * explicitly instead of reading {@code IsoPlayer.players[playerIndex]}. Drops the local-player
     * music event (irrelevant on a dedicated server). The room-seen side effects ({@code explored},
     * {@code onSee()}, {@code seen}) do not affect the {@code visible} grid.
     */
    public static void checkRoomSeen(IsoGridSquare sq, IsoPlayer player) {
        IsoRoom room = sq.getRoom();
        if (room == null || room.def == null || room.def.explored) {
            return;
        }
        if (player == null || sq.z != PZMath.fastfloor(player.getZ())) {
            return;
        }
        int dist = 10;
        if (player.getBuilding() == room.building) {
            dist = 50;
        }
        if (IsoUtils.DistanceToSquared(player.getX(), player.getY(), sq.x + 0.5F, sq.y + 0.5F)
                < dist * dist) {
            room.def.explored = true;
            room.onSee();
            room.seen = 0;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reflection bridge
    // ------------------------------------------------------------------------------------------

    private static void ensureInit() {
        if (initialized) {
            return;
        }
        synchronized (StormServerLos.class) {
            if (initialized) {
                return;
            }
            try {
                Class<?> pd = Class.forName(PLAYER_DATA_CLASS);
                fPlayer = pd.getDeclaredField("player");
                fPx = pd.getDeclaredField("px");
                fPy = pd.getDeclaredField("py");
                fPz = pd.getDeclaredField("pz");
                fVisible = pd.getDeclaredField("visible");
                fStatus = pd.getDeclaredField("status");
                setAccessible(fPlayer, fPx, fPy, fPz, fVisible, fStatus);

                Class<?> serverLos = Class.forName(SERVER_LOS_CLASS);
                fInstance = serverLos.getDeclaredField("instance");
                fPlayersMain = serverLos.getDeclaredField("playersMain");
                fPlayersLos = serverLos.getDeclaredField("playersLos");
                fMapLoading = serverLos.getDeclaredField("mapLoading");
                fSuspended = serverLos.getDeclaredField("suspended");
                setAccessible(fInstance, fPlayersMain, fPlayersLos, fMapLoading, fSuspended);

                fNotifier = Class.forName(LOS_THREAD_CLASS).getDeclaredField("notifier");
                setAccessible(fNotifier);

                Class<?> updateStatus = Class.forName(UPDATE_STATUS_CLASS);
                for (Object c : updateStatus.getEnumConstants()) {
                    String name = ((Enum<?>) c).name();
                    if ("WaitingInLOS".equals(name)) {
                        stWaiting = c;
                    } else if ("BusyInLOS".equals(name)) {
                        stBusy = c;
                    } else if ("ReadyInLOS".equals(name)) {
                        stReady = c;
                    }
                }
                if (stWaiting == null || stBusy == null || stReady == null) {
                    throw new IllegalStateException("ServerLOS$UpdateStatus constants not found");
                }

                initialized = true;
                LOGGER.info("StormServerLos: ServerLOS reflection bridge initialized");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "StormServerLos: failed to resolve ServerLOS internals; PZ may have changed",
                        e);
            }
        }
    }

    private static void setAccessible(Field... fields) {
        for (Field f : fields) {
            f.setAccessible(true);
        }
    }
}
