package io.pzstorm.storm.advice.cutawayvisit;

import io.pzstorm.storm.logging.StormLogger;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.fboRenderChunk.FBORenderCutaways;

/**
 * Replacement body for {@code FBORenderCutaways.cutawayVisit}, wired in by {@code
 * CutawayVisitFastPathPatch}. Two changes over vanilla, both behavior-preserving:
 *
 * <ol>
 *   <li><b>Visited-square dedupe without HashSet churn.</b> Vanilla pays a {@code HashSet.contains}
 *       plus (on first visit) a node-allocating {@code HashSet.add} for every cell of every wall,
 *       re-paying the {@code contains} for every point of interest. This class keeps an
 *       open-addressing identity table ({@link #markKeys}/{@link #markBits}, zero allocation
 *       steady-state) for the per-cell check+mark and only touches the vanilla {@code
 *       cutawayVisitorVisitedNorth/West} sets on first visit — one {@code add} per unique square,
 *       exactly the adds vanilla performs. The vanilla sets stay authoritative because {@code
 *       doCutawayVisitSquares} reads them for its per-POI skip check; keeping their membership
 *       identical to vanilla is what makes the results parity argument local to this method.
 *   <li><b>Per-wall chunk hoist.</b> Vanilla resolves the chunk map window per cell ({@code
 *       chunkMap.getGridSquare} for north walls, {@code cell.getGridSquare} for west walls). Walls
 *       are built inside a single chunk's 8x8 grid ({@code recreateLevel_AllWalls}), so the chunk
 *       (per chunk map for west walls, mirroring {@code IsoCell.getGridSquare}'s
 *       first-non-null-across-players order) is resolved once per wall and cells are fetched with
 *       {@code IsoChunk.getGridSquare} direct array access. The local coordinates use the same
 *       {@code getWorldXMinTiles()} subtraction as {@code
 *       IsoChunkMap.worldSquareToChunkMapSquareX}, and the window bounds are chunk-granular, so a
 *       wall whose first cell resolves resolves for all cells. A wall that unexpectedly crosses a
 *       chunk edge falls back to the vanilla per-cell lookup for that wall.
 * </ol>
 *
 * <p><b>Mark-table lifecycle.</b> The vanilla visited sets are cleared once per {@code
 * doCutawayVisitSquares} pass, before the first {@code cutawayVisit} call, and only ever populated
 * inside {@code cutawayVisit}. Both sets being empty at entry therefore identifies a fresh pass and
 * the mark table is cleared to match; while either set is non-empty the pass is in flight and marks
 * persist across the per-POI calls. Marks from the final pass pin their squares until the next
 * pass, which is the same pinning behavior the vanilla {@code PerPlayerData} sets already have.
 *
 * <p><b>Skipped-when-visited lookups.</b> For an already-visited square vanilla still calls {@code
 * getCutawayDataForLevel} (lazily-creating) and {@code shouldRenderSquare} (pure) before the {@code
 * !bVisited} test short-circuits. This path skips both: the first visit of the square already
 * performed the identical {@code getCutawayDataForLevel(chunk, level)} creation ({@code level} is
 * uniform across a pass — every wall comes from {@code getCutawayDataForLevel(playerZ).allWalls}),
 * and {@code shouldRenderSquare} only reads {@code squareFlags}.
 *
 * <p><b>Fail soft.</b> Any {@link Throwable} logs once, latches {@link #failed} and returns {@code
 * false} so the vanilla body runs — for the in-flight call and every call after. A partial fast run
 * can leave at most the square being processed marked visited but not yet result-added; the vanilla
 * rerun then skips it for that one pass, and the next pass recomputes everything from vanilla code.
 *
 * <p>Single-threaded by construction: {@code cutawayVisit} runs on the render main thread, same as
 * the unsynchronized vanilla HashSet accesses it replaces.
 */
public final class CutawayVisitFastPath {

    private static final int MARK_NORTH = 1;
    private static final int MARK_WEST = 2;

    /** Permanent revert-to-vanilla latch; set on the first {@link Throwable} out of the body. */
    private static boolean failed;

    private static boolean initialized;

    private static Field perPlayerDataField;
    private static Field wallsField;
    private static Field visitedNorthField;
    private static Field visitedWestField;
    private static Field resultsNorthField;
    private static Field resultsWestField;
    private static Field wallChunkLevelDataField;

    /**
     * {@code private boolean FBORenderCutaways.IsCutawaySquare(CutawayWall, IsoGridSquare,
     * IsoGridSquare, long)}.
     */
    private static MethodHandle isCutawaySquare;

    /** Open-addressing identity table: visited marks for the current pass. */
    private static Object[] markKeys = new Object[1024];

    private static byte[] markBits = new byte[1024];
    private static int markCount;

    /**
     * Per-wall scratch for the west-wall multi-chunk-map hoist ({@code IsoPlayer.numPlayers} <= 4).
     */
    private static final IsoChunk[] MAP_CHUNKS = new IsoChunk[4];

    private static final int[] MAP_LX = new int[4];
    private static final int[] MAP_LY = new int[4];

    private CutawayVisitFastPath() {}

    /**
     * Runs the fast {@code cutawayVisit} body.
     *
     * @param fboObj the {@code FBORenderCutaways} instance ({@code @Advice.This}; typed {@code
     *     Object} so the advice never references the transform target)
     * @param poiSquareObj the point-of-interest {@code IsoGridSquare}
     * @param currentTimeMillis vanilla's second argument, forwarded to {@code IsCutawaySquare}
     * @return {@code true} if the fast body ran (the advice skips the vanilla body); {@code false}
     *     to fall through to vanilla (failure latch)
     */
    public static boolean visit(Object fboObj, Object poiSquareObj, long currentTimeMillis) {
        if (failed) {
            return false;
        }
        try {
            ensureInit();
            return runInner(
                    (FBORenderCutaways) fboObj, (IsoGridSquare) poiSquareObj, currentTimeMillis);
        } catch (Throwable t) {
            failed = true;
            StormLogger.LOGGER.error(
                    "CutawayVisitFastPath failed — reverting to vanilla"
                            + " FBORenderCutaways.cutawayVisit",
                    t);
            return false;
        }
    }

    private static void ensureInit() throws Exception {
        if (initialized) {
            return;
        }
        Class<?> perPlayerDataClass =
                Class.forName(
                        "zombie.iso.fboRenderChunk.FBORenderCutaways$PerPlayerData",
                        false,
                        FBORenderCutaways.class.getClassLoader());
        perPlayerDataField = FBORenderCutaways.class.getDeclaredField("perPlayerData");
        perPlayerDataField.setAccessible(true);
        wallsField = perPlayerDataClass.getDeclaredField("cutawayWalls");
        wallsField.setAccessible(true);
        visitedNorthField = perPlayerDataClass.getDeclaredField("cutawayVisitorVisitedNorth");
        visitedNorthField.setAccessible(true);
        visitedWestField = perPlayerDataClass.getDeclaredField("cutawayVisitorVisitedWest");
        visitedWestField.setAccessible(true);
        resultsNorthField = perPlayerDataClass.getDeclaredField("cutawayVisitorResultsNorth");
        resultsNorthField.setAccessible(true);
        resultsWestField = perPlayerDataClass.getDeclaredField("cutawayVisitorResultsWest");
        resultsWestField.setAccessible(true);
        wallChunkLevelDataField =
                FBORenderCutaways.CutawayWall.class.getDeclaredField("chunkLevelData");
        wallChunkLevelDataField.setAccessible(true);
        Method m =
                FBORenderCutaways.class.getDeclaredMethod(
                        "IsCutawaySquare",
                        FBORenderCutaways.CutawayWall.class,
                        IsoGridSquare.class,
                        IsoGridSquare.class,
                        long.class);
        m.setAccessible(true);
        isCutawaySquare = MethodHandles.lookup().unreflect(m);
        initialized = true;
    }

    @SuppressWarnings("unchecked")
    private static boolean runInner(FBORenderCutaways fbo, IsoGridSquare poiSquare, long now)
            throws Throwable {
        int playerIndex = IsoCamera.frameState.playerIndex;
        IsoCell cell = fbo.cell;
        IsoChunkMap chunkMap = cell.chunkMap[playerIndex];
        if (chunkMap == null || chunkMap.ignore) {
            return true;
        }
        Object ppd = ((Object[]) perPlayerDataField.get(fbo))[playerIndex];
        ArrayList<?> walls = (ArrayList<?>) wallsField.get(ppd);
        if (walls.isEmpty()) {
            return true;
        }
        HashSet<Object> visitedNorth = (HashSet<Object>) visitedNorthField.get(ppd);
        HashSet<Object> visitedWest = (HashSet<Object>) visitedWestField.get(ppd);
        HashSet<Object> resultsNorth = (HashSet<Object>) resultsNorthField.get(ppd);
        HashSet<Object> resultsWest = (HashSet<Object>) resultsWestField.get(ppd);
        if (visitedNorth.isEmpty() && visitedWest.isEmpty()) {
            // doCutawayVisitSquares cleared the sets since the last call: new pass
            clearMarks();
        }
        int z = poiSquare.z;
        boolean zInRange = z >= -32 && z <= 31;
        for (int j = 0; j < walls.size(); j++) {
            FBORenderCutaways.CutawayWall wall = (FBORenderCutaways.CutawayWall) walls.get(j);
            int level =
                    ((FBORenderCutaways.ChunkLevelData) wallChunkLevelDataField.get(wall)).level;
            if (wall.y1 == wall.y2) {
                visitNorthWall(
                        fbo,
                        chunkMap,
                        wall,
                        level,
                        playerIndex,
                        poiSquare,
                        z,
                        zInRange,
                        now,
                        visitedNorth,
                        resultsNorth,
                        resultsWest);
            } else {
                visitWestWall(
                        fbo,
                        cell,
                        wall,
                        level,
                        playerIndex,
                        poiSquare,
                        z,
                        zInRange,
                        now,
                        visitedWest,
                        resultsWest,
                        resultsNorth);
            }
        }
        return true;
    }

    private static void visitNorthWall(
            FBORenderCutaways fbo,
            IsoChunkMap chunkMap,
            FBORenderCutaways.CutawayWall wall,
            int level,
            int playerIndex,
            IsoGridSquare poiSquare,
            int z,
            boolean zInRange,
            long now,
            HashSet<Object> visited,
            HashSet<Object> results,
            HashSet<Object> crossResults)
            throws Throwable {
        int x1 = wall.x1;
        int x2 = wall.x2;
        int y = wall.y1;
        if (x1 >= x2) {
            return;
        }
        if (((x1 ^ (x2 - 1)) & ~7) != 0) {
            // never expected (walls are built within one chunk) — vanilla per-cell lookup
            for (int x = x1; x < x2; x++) {
                visitSquare(
                        fbo,
                        chunkMap.getGridSquare(x, y, z),
                        wall,
                        level,
                        playerIndex,
                        poiSquare,
                        now,
                        MARK_NORTH,
                        visited,
                        results,
                        crossResults);
            }
            return;
        }
        if (!zInRange) {
            return;
        }
        IsoChunk chunk = chunkMap.getChunkForGridSquare(x1, y);
        if (chunk == null || !chunk.loaded) {
            return;
        }
        int lx = (x1 - chunkMap.getWorldXMinTiles()) % 8;
        int ly = (y - chunkMap.getWorldYMinTiles()) % 8;
        for (int i = 0; i < x2 - x1; i++) {
            visitSquare(
                    fbo,
                    chunk.getGridSquare(lx + i, ly, z),
                    wall,
                    level,
                    playerIndex,
                    poiSquare,
                    now,
                    MARK_NORTH,
                    visited,
                    results,
                    crossResults);
        }
    }

    private static void visitWestWall(
            FBORenderCutaways fbo,
            IsoCell cell,
            FBORenderCutaways.CutawayWall wall,
            int level,
            int playerIndex,
            IsoGridSquare poiSquare,
            int z,
            boolean zInRange,
            long now,
            HashSet<Object> visited,
            HashSet<Object> results,
            HashSet<Object> crossResults)
            throws Throwable {
        int x = wall.x1;
        int y1 = wall.y1;
        int y2 = wall.y2;
        if (y1 >= y2) {
            return;
        }
        if (((y1 ^ (y2 - 1)) & ~7) != 0) {
            for (int y = y1; y < y2; y++) {
                visitSquare(
                        fbo,
                        cell.getGridSquare(x, y, z),
                        wall,
                        level,
                        playerIndex,
                        poiSquare,
                        now,
                        MARK_WEST,
                        visited,
                        results,
                        crossResults);
            }
            return;
        }
        if (!zInRange) {
            return;
        }
        // vanilla uses IsoCell.getGridSquare here: first non-null across players' chunk maps
        int mapCount = 0;
        for (int n = 0; n < IsoPlayer.numPlayers; n++) {
            IsoChunkMap cm = cell.chunkMap[n];
            if (cm.ignore) {
                continue;
            }
            IsoChunk chunk = cm.getChunkForGridSquare(x, y1);
            if (chunk == null || !chunk.loaded) {
                continue;
            }
            MAP_CHUNKS[mapCount] = chunk;
            MAP_LX[mapCount] = (x - cm.getWorldXMinTiles()) % 8;
            MAP_LY[mapCount] = (y1 - cm.getWorldYMinTiles()) % 8;
            mapCount++;
        }
        if (mapCount != 0) {
            for (int i = 0; i < y2 - y1; i++) {
                IsoGridSquare sq = null;
                for (int k = 0; k < mapCount; k++) {
                    sq = MAP_CHUNKS[k].getGridSquare(MAP_LX[k], MAP_LY[k] + i, z);
                    if (sq != null) {
                        break;
                    }
                }
                visitSquare(
                        fbo,
                        sq,
                        wall,
                        level,
                        playerIndex,
                        poiSquare,
                        now,
                        MARK_WEST,
                        visited,
                        results,
                        crossResults);
            }
            Arrays.fill(MAP_CHUNKS, 0, mapCount, null);
        }
    }

    /**
     * The vanilla per-cell body: mark visited, then on first visit run the render-flag / non-empty
     * / {@code IsCutawaySquare} chain and record results. {@code crossResults} is the opposite
     * direction's results set for the {@code WallSE} corner case.
     */
    private static void visitSquare(
            FBORenderCutaways fbo,
            IsoGridSquare sq,
            FBORenderCutaways.CutawayWall wall,
            int level,
            int playerIndex,
            IsoGridSquare poiSquare,
            long now,
            int markBit,
            HashSet<Object> visited,
            HashSet<Object> results,
            HashSet<Object> crossResults)
            throws Throwable {
        if (sq == null || checkAndMark(sq, markBit)) {
            return;
        }
        visited.add(sq);
        FBORenderCutaways.ChunkLevelData levelData = sq.chunk.getCutawayDataForLevel(level);
        if (levelData.shouldRenderSquare(playerIndex, sq)
                && !sq.getObjects().isEmpty()
                && (boolean) isCutawaySquare.invokeExact(fbo, wall, poiSquare, sq, now)) {
            results.add(sq);
            if (sq.has(IsoFlagType.WallSE)) {
                crossResults.add(sq);
            }
        }
    }

    private static void clearMarks() {
        if (markCount != 0) {
            Arrays.fill(markKeys, null);
            markCount = 0;
        }
    }

    /**
     * Checks and sets {@code markBit} for {@code key} in one identity-keyed probe.
     *
     * @return {@code true} if the bit was already set (square already visited in this direction)
     */
    private static boolean checkAndMark(Object key, int markBit) {
        Object[] keys = markKeys;
        int mask = keys.length - 1;
        int h = System.identityHashCode(key);
        int i = (h ^ h >>> 16) & mask;
        while (true) {
            Object k = keys[i];
            if (k == key) {
                int old = markBits[i];
                markBits[i] = (byte) (old | markBit);
                return (old & markBit) != 0;
            }
            if (k == null) {
                keys[i] = key;
                markBits[i] = (byte) markBit;
                markCount++;
                if (markCount * 3 > keys.length * 2) {
                    grow();
                }
                return false;
            }
            i = (i + 1) & mask;
        }
    }

    private static void grow() {
        Object[] oldKeys = markKeys;
        byte[] oldBits = markBits;
        Object[] newKeys = new Object[oldKeys.length * 2];
        byte[] newBits = new byte[oldKeys.length * 2];
        int mask = newKeys.length - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            Object k = oldKeys[i];
            if (k == null) {
                continue;
            }
            int h = System.identityHashCode(k);
            int j = (h ^ h >>> 16) & mask;
            while (newKeys[j] != null) {
                j = (j + 1) & mask;
            }
            newKeys[j] = k;
            newBits[j] = oldBits[i];
        }
        markKeys = newKeys;
        markBits = newBits;
    }
}
