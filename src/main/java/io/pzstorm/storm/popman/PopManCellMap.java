package io.pzstorm.storm.popman;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every cell the population currently holds in memory, and the one place a cell comes into
 * existence.
 *
 * <p>A cell is loaded from its save file if it has one and given a whole starting population if it
 * does not, and only then are its running totals computed. That order matters: the repopulation
 * pass reads a neighbour's totals immediately after triggering its load.
 */
public final class PopManCellMap {

    /** Fills a cell from its save file. Returns false when the cell has never been saved. */
    public interface Loader {
        boolean load(PopManCell cell);
    }

    private final Map<Long, PopManCell> cells = new HashMap<>();
    private final PopManConfig config;
    private final PopManMap world;
    private final Loader loader;

    public PopManCellMap(PopManConfig config, PopManMap world, Loader loader) {
        this.config = config;
        this.world = world;
        this.loader = loader;
    }

    private static long key(int cellX, int cellY) {
        return ((long) cellX << 32) | (cellY & 0xFFFFFFFFL);
    }

    /** The cell if it is already resident, otherwise null. Never loads. */
    public PopManCell resident(int cellX, int cellY) {
        return cells.get(key(cellX, cellY));
    }

    public List<PopManCell> resident() {
        return new ArrayList<>(cells.values());
    }

    /** The cells that get a repopulation pass: resident and claimed by something in the world. */
    public List<PopManCell> active() {
        List<PopManCell> active = new ArrayList<>();
        for (PopManCell cell : cells.values()) {
            if (cell.active) {
                active.add(cell);
            }
        }
        return active;
    }

    public boolean inWorld(int cellX, int cellY) {
        return cellX >= world.minCellX()
                && cellY >= world.minCellY()
                && cellX < world.minCellX() + world.widthCells()
                && cellY < world.minCellY() + world.heightCells();
    }

    public int size() {
        return cells.size();
    }

    public PopManCell load(int cellX, int cellY, double worldAgeHours, long nowMs) {
        PopManCell cell = peek(cellX, cellY, worldAgeHours, nowMs);
        cell.active = true;
        return cell;
    }

    /**
     * Makes a cell resident without activating it. Vanilla ({@code FUN_1800190e0}) loads a
     * neighbour just to read its totals and tears it straight down again, so a peeked cell never
     * gets a repopulation pass of its own; here it simply sits inactive until it idles out. Loading
     * it once and letting eviction reclaim it is what keeps the neighbour totals stable from tick
     * to tick without re-rolling a virgin population every time.
     */
    public PopManCell peek(int cellX, int cellY, double worldAgeHours, long nowMs) {
        PopManCell existing = cells.get(key(cellX, cellY));
        if (existing != null) {
            existing.lastTouchedMs = nowMs;
            return existing;
        }
        PopManCell cell = new PopManCell(cellX, cellY);
        cell.loaded = true;
        cell.lastTouchedMs = nowMs;
        cell.loadedFromDisk = loader != null && loader.load(cell);
        if (!cell.loadedFromDisk) {
            PopManSpawner.populateVirginCell(cell, config, world, worldAgeHours);
        }
        cell.recomputeAggregates();
        cells.put(key(cellX, cellY), cell);
        return cell;
    }

    public PopManCell loadForSquare(int squareX, int squareY, double worldAgeHours, long nowMs) {
        return load(
                PopManGeometry.cellOfSquare(squareX),
                PopManGeometry.cellOfSquare(squareY),
                worldAgeHours,
                nowMs);
    }

    /**
     * The cell owning a square, without creating one. Squares outside the world, and squares in
     * cells nobody has visited, have no cell — asking must not conjure a fully populated one, which
     * is why this is separate from {@link #loadForSquare}.
     */
    public PopManCell residentForSquare(int squareX, int squareY) {
        return residentInWorld(
                PopManGeometry.cellOfSquare(squareX), PopManGeometry.cellOfSquare(squareY));
    }

    /** As {@link #residentForSquare}, for a chunk coordinate. */
    public PopManCell residentForChunk(int chunkX, int chunkY) {
        return residentInWorld(
                PopManGeometry.cellOfChunk(chunkX), PopManGeometry.cellOfChunk(chunkY));
    }

    private PopManCell residentInWorld(int cellX, int cellY) {
        return inWorld(cellX, cellY) ? resident(cellX, cellY) : null;
    }

    /**
     * Marks every chunk a player is streaming in as seen right now, so that leaving an area starts
     * its unseen-respawn grace period rather than finding it already expired.
     *
     * <p>Vanilla ({@code FUN_18001e620}) computes the chunk window from the rectangle's
     * <em>first</em> cell and then reuses that window for every cell the rectangle spans, so chunks
     * on the far side of a cell boundary are silently never refreshed and lose their grace period
     * entirely. Reproduced by default because respawn timing is visible to players and a server
     * that quietly respawns differently from vanilla is worse than one that respawns wrongly in the
     * same way; pass {@code true} to refresh every chunk the rectangle actually covers.
     *
     * <p>Returns every cell it touched. Eviction runs in the same tick and would otherwise drop the
     * cell a player is standing in, two seconds after it loaded, on a loop.
     */
    public Set<PopManCell> refreshSeenClocks(
            PopManLoadedAreas areas, double worldAgeHours, long nowMs, boolean spanWholeArea) {
        Set<PopManCell> touched = new HashSet<>();
        float age = (float) worldAgeHours;
        int[] packed = areas.packed();
        for (int i = 0; i < packed.length; i += 4) {
            int chunkX = packed[i];
            int chunkY = packed[i + 1];
            int lastChunkX = chunkX + packed[i + 2] - 1;
            int lastChunkY = chunkY + packed[i + 3] - 1;
            int firstCellX = PopManGeometry.cellOfChunk(chunkX);
            int firstCellY = PopManGeometry.cellOfChunk(chunkY);

            for (int cy = firstCellY; cy <= PopManGeometry.cellOfChunk(lastChunkY); cy++) {
                for (int cx = firstCellX; cx <= PopManGeometry.cellOfChunk(lastChunkX); cx++) {
                    if (!inWorld(cx, cy)) {
                        continue;
                    }
                    PopManCell cell = load(cx, cy, worldAgeHours, nowMs);
                    touched.add(cell);
                    int clampCellX = spanWholeArea ? cx : firstCellX;
                    int clampCellY = spanWholeArea ? cy : firstCellY;
                    int minX = Math.max(chunkX, clampCellX * PopManGeometry.CHUNKS_PER_CELL);
                    int minY = Math.max(chunkY, clampCellY * PopManGeometry.CHUNKS_PER_CELL);
                    int maxX =
                            Math.min(
                                    lastChunkX,
                                    (clampCellX + 1) * PopManGeometry.CHUNKS_PER_CELL - 1);
                    int maxY =
                            Math.min(
                                    lastChunkY,
                                    (clampCellY + 1) * PopManGeometry.CHUNKS_PER_CELL - 1);
                    for (int y = minY; y <= maxY; y++) {
                        for (int x = minX; x <= maxX; x++) {
                            if (PopManGeometry.cellOfChunk(x) != cx
                                    || PopManGeometry.cellOfChunk(y) != cy) {
                                continue;
                            }
                            PopManChunk chunk = cell.chunkAt(x, y);
                            if (chunk != null && chunk.lastSeenTime != age) {
                                chunk.lastSeenTime = age;
                                cell.dirty = true;
                            }
                        }
                    }
                }
            }
        }
        return touched;
    }

    /** How long a cell must sit untouched before it is a candidate for eviction. */
    public static final long IDLE_MS = 2000;

    /**
     * Drops cells nothing is using any more and returns them, so the caller can write out the ones
     * that changed. Eviction is what keeps memory flat on a large map; without it every cell any
     * player has ever walked through stays resident for the life of the server.
     *
     * <p>A cell is held back while a repopulation path job is still in flight against it, and while
     * any horde is standing in it — a group holds its members outside every chunk, so unloading its
     * cell would delete them.
     */
    public List<PopManCell> evictIdle(long nowMs, Set<PopManCell> touched) {
        List<PopManCell> evicted = new ArrayList<>();
        for (Iterator<PopManCell> it = cells.values().iterator(); it.hasNext(); ) {
            PopManCell cell = it.next();
            if (touched.contains(cell)
                    || cell.outstandingTasks != 0
                    || !cell.groups.isEmpty()
                    || cell.lastTouchedMs + IDLE_MS >= nowMs) {
                continue;
            }
            it.remove();
            evicted.add(cell);
        }
        return evicted;
    }

    public void unload(int cellX, int cellY) {
        cells.remove(key(cellX, cellY));
    }
}
