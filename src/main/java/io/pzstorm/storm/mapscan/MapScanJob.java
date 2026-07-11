package io.pzstorm.storm.mapscan;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import zombie.ZomboidFileSystem;
import zombie.core.ImportantArea;
import zombie.core.ImportantAreaManager;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.network.ServerMap;

/**
 * Whole-map reachability sweep: walks the map in windows of server cells, force-loads each window
 * through the engine's own relevancy path ({@link ImportantAreaManager}), runs {@link
 * ReachabilityScanEngine} on the resident chunks, stitches verdicts across windows with {@link
 * MapScanCollector}, and finally writes the no-spawn artifact + report via {@link NoSpawnMapWriter}
 * into the current save directory.
 *
 * <p>All work happens on the server main thread via {@link #pump()} (called every tick from {@code
 * ServerTickAdvice}); the {@code stormscanmap} command only files requests through atomics, since
 * commands may execute off the main thread.
 *
 * <p>Windows overlap by one server cell (8 chunks), which {@link MapScanCollector} requires for
 * exact cross-window stitching. Cells are held loaded by refreshing their important-area every tick
 * and released by explicit removal — the 10s expiry never fires on a paused-empty server.
 *
 * <p>Intended for a scratch/staging server: first-time chunk loads still run world-gen side effects
 * (loot, corpses, zone stories) and persist those chunks. Indoor zombie spawning is suppressed
 * while a scan runs (see {@code TryAddIndoorZombiesSkipAdvice}) so the sweep doesn't mass-spawn
 * zombies into every unexplored building — including into the traps being mapped.
 */
public final class MapScanJob {

    private static final int TILES_PER_SERVER_CELL = 64;
    private static final int CHUNKS_PER_SERVER_CELL = 8;
    private static final int TILES_PER_META_CELL = 256;
    private static final int DEFAULT_WINDOW_CELLS = 5;
    private static final long WINDOW_LOAD_TIMEOUT_MILLIS = 180_000L;

    /** Sentinel for "no explicit bounds — scan the whole map". */
    public static final int FULL_MAP = Integer.MIN_VALUE;

    private enum State {
        IDLE,
        RUNNING,
        FINISHED,
        FAILED
    }

    // -- cross-thread interface (command thread <-> main thread) --
    private static final AtomicReference<int[]> START_REQUEST = new AtomicReference<>();
    private static final AtomicBoolean STOP_REQUEST = new AtomicBoolean();
    private static volatile boolean running = false;
    private static volatile String statusLine = "idle";

    // -- main-thread state --
    private static State state = State.IDLE;
    private static ArrayList<int[]> windows;
    private static int windowIndex;
    private static long windowStartMillis;
    private static long scanStartMillis;
    private static int windowsFailed;
    private static MapScanCollector collector;
    private static String boundsDescription = "";
    private static final HashSet<Long> ownedAreas = new HashSet<>();

    private MapScanJob() {}

    /**
     * Read every {@code tryAddIndoorZombies} call while a scan is running (from inlined advice).
     */
    public static boolean isSuppressingIndoorSpawns() {
        return running;
    }

    public static String requestStart(
            int minTileX, int minTileY, int maxTileX, int maxTileY, int windowCells) {
        if (running) {
            return "A map scan is already running - see 'stormscanmap status'.";
        }
        START_REQUEST.set(new int[] {minTileX, minTileY, maxTileX, maxTileY, windowCells});
        return "Map scan queued; it starts on the next server tick. Watch 'stormscanmap status'.";
    }

    public static String requestStop() {
        if (!running && START_REQUEST.get() == null) {
            return "No map scan is running.";
        }
        START_REQUEST.set(null);
        STOP_REQUEST.set(true);
        return "Stopping map scan.";
    }

    public static String status() {
        return statusLine;
    }

    /** Called once per server tick from {@code ServerTickAdvice}. Main thread only. */
    public static void pump() {
        if (!GameServer.server) {
            return;
        }
        if (STOP_REQUEST.compareAndSet(true, false)) {
            if (state == State.RUNNING) {
                abort("stopped by request");
            }
        }
        int[] request = START_REQUEST.getAndSet(null);
        if (request != null && state != State.RUNNING) {
            begin(request);
        }
        if (state != State.RUNNING) {
            return;
        }
        step();
    }

    private static void begin(int[] request) {
        IsoMetaGrid metaGrid = metaGrid();
        if (metaGrid == null) {
            state = State.FAILED;
            statusLine = "failed: world/meta grid not loaded yet";
            return;
        }
        int minTileX =
                request[0] == FULL_MAP ? metaGrid.getMinX() * TILES_PER_META_CELL : request[0];
        int minTileY =
                request[1] == FULL_MAP ? metaGrid.getMinY() * TILES_PER_META_CELL : request[1];
        int maxTileX =
                request[2] == FULL_MAP
                        ? (metaGrid.getMaxX() + 1) * TILES_PER_META_CELL - 1
                        : request[2];
        int maxTileY =
                request[3] == FULL_MAP
                        ? (metaGrid.getMaxY() + 1) * TILES_PER_META_CELL - 1
                        : request[3];
        int windowCells = request[4] > 1 ? request[4] : DEFAULT_WINDOW_CELLS;

        int scMinX = Math.floorDiv(minTileX, TILES_PER_SERVER_CELL);
        int scMinY = Math.floorDiv(minTileY, TILES_PER_SERVER_CELL);
        int scMaxX = Math.floorDiv(maxTileX, TILES_PER_SERVER_CELL);
        int scMaxY = Math.floorDiv(maxTileY, TILES_PER_SERVER_CELL);

        windows = new ArrayList<>();
        int step = windowCells - 1;
        for (int wy = scMinY; wy <= scMaxY; wy += step) {
            for (int wx = scMinX; wx <= scMaxX; wx += step) {
                int[] window = {
                    wx,
                    wy,
                    Math.min(wx + windowCells - 1, scMaxX),
                    Math.min(wy + windowCells - 1, scMaxY)
                };
                if (windowHasLotData(window)) {
                    windows.add(window);
                }
            }
        }
        if (windows.isEmpty()) {
            state = State.FAILED;
            statusLine = "failed: no map data in the requested bounds";
            return;
        }

        boundsDescription =
                "tiles ("
                        + minTileX
                        + ","
                        + minTileY
                        + ")-("
                        + maxTileX
                        + ","
                        + maxTileY
                        + "), "
                        + windowCells
                        + "x"
                        + windowCells
                        + " server-cell windows";
        collector = new MapScanCollector();
        windowIndex = 0;
        windowsFailed = 0;
        scanStartMillis = System.currentTimeMillis();
        windowStartMillis = scanStartMillis;
        state = State.RUNNING;
        running = true;
        statusLine = "starting: " + windows.size() + " windows, " + boundsDescription;
        LOGGER.info("Map scan started: {} windows, {}", windows.size(), boundsDescription);
    }

    private static void step() {
        int[] window = windows.get(windowIndex);
        refreshAreas(window);
        int[] residency = countResidentChunks(window);
        int resident = residency[0];
        int expected = residency[1];
        if (resident < expected) {
            if (System.currentTimeMillis() - windowStartMillis > WINDOW_LOAD_TIMEOUT_MILLIS) {
                windowsFailed++;
                LOGGER.error(
                        "Map scan window {}/{} timed out loading ({}/{} chunks) - failing open",
                        windowIndex + 1,
                        windows.size(),
                        resident,
                        expected);
                advance();
                return;
            }
            statusLine =
                    "window "
                            + (windowIndex + 1)
                            + "/"
                            + windows.size()
                            + ": loading "
                            + resident
                            + "/"
                            + expected
                            + " chunks | candidates="
                            + collector.candidateSquareCount()
                            + " | elapsed="
                            + elapsedSeconds()
                            + "s";
            return;
        }

        long analyzeStart = System.currentTimeMillis();
        WindowScanResult result =
                ReachabilityScanEngine.scan(
                        window[0] * CHUNKS_PER_SERVER_CELL,
                        window[1] * CHUNKS_PER_SERVER_CELL,
                        (window[2] + 1) * CHUNKS_PER_SERVER_CELL - 1,
                        (window[3] + 1) * CHUNKS_PER_SERVER_CELL - 1);
        collector.addWindow(result);
        LOGGER.debug(
                "Map scan window {}/{}: {} standable, {} reached, {} components, {}ms",
                windowIndex + 1,
                windows.size(),
                result.standableCount,
                result.reachedCount,
                result.getComponents().size(),
                System.currentTimeMillis() - analyzeStart);
        advance();
    }

    private static void advance() {
        windowIndex++;
        if (windowIndex >= windows.size()) {
            finish();
            return;
        }
        releaseAreasExcept(windows.get(windowIndex));
        windowStartMillis = System.currentTimeMillis();
        statusLine =
                "window "
                        + (windowIndex + 1)
                        + "/"
                        + windows.size()
                        + ": loading | candidates="
                        + collector.candidateSquareCount()
                        + " | elapsed="
                        + elapsedSeconds()
                        + "s";
    }

    private static void finish() {
        releaseAreasExcept(null);
        MapScanCollector.FinalResult result;
        try {
            result = collector.finish(MapScanJob::chunkHasLotData);
            File artifact =
                    ZomboidFileSystem.instance.getFileInCurrentSave("storm-no-spawn-map.bin");
            File report =
                    ZomboidFileSystem.instance.getFileInCurrentSave("storm-map-scan-report.txt");
            NoSpawnMapWriter.writeArtifact(artifact, result.sealedRegions);
            NoSpawnMapWriter.writeReport(
                    report,
                    result,
                    boundsDescription,
                    System.currentTimeMillis() - scanStartMillis,
                    windows.size(),
                    windowsFailed);
            state = State.FINISHED;
            statusLine =
                    "finished in "
                            + elapsedSeconds()
                            + "s: "
                            + result.sealedRegions.size()
                            + " sealed regions ("
                            + result.sealedSquareCount()
                            + " squares), "
                            + windowsFailed
                            + " windows failed | artifact: "
                            + artifact.getPath()
                            + " | report: "
                            + report.getPath();
            LOGGER.info("Map scan {}", statusLine);
        } catch (Exception e) {
            state = State.FAILED;
            statusLine = "failed while writing results: " + e;
            LOGGER.error("Map scan failed while writing results", e);
        } finally {
            running = false;
            collector = null;
            windows = null;
        }
    }

    private static void abort(String reason) {
        releaseAreasExcept(null);
        running = false;
        collector = null;
        windows = null;
        state = State.IDLE;
        statusLine = "idle (" + reason + ")";
        LOGGER.info("Map scan aborted: {}", reason);
    }

    /**
     * Keeps this window's lot-data cells registered as important areas (10s expiry, so per tick).
     */
    private static void refreshAreas(int[] window) {
        for (int sy = window[1]; sy <= window[3]; sy++) {
            for (int sx = window[0]; sx <= window[2]; sx++) {
                if (!serverCellHasLotData(sx, sy)) {
                    continue;
                }
                ImportantAreaManager.getInstance()
                        .updateOrAdd(sx * TILES_PER_SERVER_CELL, sy * TILES_PER_SERVER_CELL);
                ownedAreas.add(CandidateComponent.packChunk(sx, sy));
            }
        }
    }

    /**
     * Removes our important areas that the given window (null = none) doesn't need, so cells unload
     * promptly — expiry alone never fires while a paused-empty server refreshes timestamps.
     */
    private static void releaseAreasExcept(int[] window) {
        Iterator<ImportantArea> it = ImportantAreaManager.ImportantAreas.iterator();
        while (it.hasNext()) {
            ImportantArea area = it.next();
            long key = CandidateComponent.packChunk(area.sx, area.sy);
            if (!ownedAreas.contains(key)) {
                continue;
            }
            boolean keep =
                    window != null
                            && area.sx >= window[0]
                            && area.sx <= window[2]
                            && area.sy >= window[1]
                            && area.sy <= window[3];
            if (!keep) {
                it.remove();
                ownedAreas.remove(key);
            }
        }
    }

    /** Returns {resident, expected} chunk counts for the window's lot-data cells. */
    private static int[] countResidentChunks(int[] window) {
        int resident = 0;
        int expected = 0;
        for (int sy = window[1]; sy <= window[3]; sy++) {
            for (int sx = window[0]; sx <= window[2]; sx++) {
                if (!serverCellHasLotData(sx, sy)) {
                    continue;
                }
                for (int cy = 0; cy < CHUNKS_PER_SERVER_CELL; cy++) {
                    for (int cx = 0; cx < CHUNKS_PER_SERVER_CELL; cx++) {
                        expected++;
                        if (ServerMap.instance.getChunk(
                                        sx * CHUNKS_PER_SERVER_CELL + cx,
                                        sy * CHUNKS_PER_SERVER_CELL + cy)
                                != null) {
                            resident++;
                        }
                    }
                }
            }
        }
        return new int[] {resident, expected};
    }

    private static boolean windowHasLotData(int[] window) {
        for (int sy = window[1]; sy <= window[3]; sy++) {
            for (int sx = window[0]; sx <= window[2]; sx++) {
                if (serverCellHasLotData(sx, sy)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean serverCellHasLotData(int scX, int scY) {
        return metaCellHasLotData(
                Math.floorDiv(scX * TILES_PER_SERVER_CELL, TILES_PER_META_CELL),
                Math.floorDiv(scY * TILES_PER_SERVER_CELL, TILES_PER_META_CELL));
    }

    private static boolean chunkHasLotData(int chunkX, int chunkY) {
        return metaCellHasLotData(
                Math.floorDiv(chunkX * ReachabilityScanEngine.CHUNK_DIM, TILES_PER_META_CELL),
                Math.floorDiv(chunkY * ReachabilityScanEngine.CHUNK_DIM, TILES_PER_META_CELL));
    }

    private static boolean metaCellHasLotData(int metaX, int metaY) {
        IsoMetaGrid metaGrid = metaGrid();
        return metaGrid != null
                && metaGrid.hasCell(metaX - metaGrid.getMinX(), metaY - metaGrid.getMinY());
    }

    private static IsoMetaGrid metaGrid() {
        return IsoWorld.instance != null ? IsoWorld.instance.getMetaGrid() : null;
    }

    private static long elapsedSeconds() {
        return (System.currentTimeMillis() - scanStartMillis) / 1000L;
    }
}
