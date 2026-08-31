package io.pzstorm.storm.popman;

import io.pzstorm.storm.logging.StormLogger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The collision map {@code MapCollisionData} kept inside {@code PZPopMan64}: one {@link
 * PopManCollisionCell} per metagrid cell, loaded from disk on first touch and rotated out through
 * an LRU list, plus the density lookup the population reads through the same object.
 *
 * <p>Every method runs on the {@code MapCollisionData} thread, which is also the population worker,
 * so nothing here is synchronised — exactly like the native. Two eviction rules exist and both are
 * kept: touching an unloaded cell while more than {@link #RESIDENT_TARGET} are resident unloads
 * cells idle for {@link #TOUCH_IDLE_MS}, and every {@code n_update} unloads at most one cell idle
 * for {@link #UPDATE_IDLE_MS}.
 */
public final class PopManCollisionGrid implements PopManWorld, PopManPathSystem.Terrain {

    public interface DensitySource {
        int densityByte(int chunkX, int chunkY);
    }

    static final int RESIDENT_TARGET = 20;
    static final long TOUCH_IDLE_MS = 1000;
    static final long UPDATE_IDLE_MS = 5000;

    private static final DensitySource NO_DENSITY = (cx, cy) -> PopManPopulation.NO_DENSITY_DATA;

    private final LongSupplier clockMs;
    private final List<PopManCollisionCell> resident = new ArrayList<>();

    private PopManChunkDataSource files;
    private PopManMetaRegistry registry;
    private DensitySource density = NO_DENSITY;
    private BooleanSupplier noSave = () -> false;
    private PopManCollisionCell[] cells = new PopManCollisionCell[0];
    private int minCellX;
    private int minCellY;
    private int widthCells;
    private int heightCells;

    public PopManCollisionGrid() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    PopManCollisionGrid(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    /** {@code n_init}: allocates the cells for a world; nothing is loaded until touched. */
    public void init(
            PopManChunkDataSource files,
            PopManMetaRegistry registry,
            BooleanSupplier noSave,
            int minCellX,
            int minCellY,
            int widthCells,
            int heightCells) {
        this.files = files;
        this.registry = registry;
        this.density = registry == null ? NO_DENSITY : registry;
        this.noSave = noSave == null ? () -> false : noSave;
        this.minCellX = minCellX;
        this.minCellY = minCellY;
        this.widthCells = widthCells;
        this.heightCells = heightCells;
        this.cells = new PopManCollisionCell[Math.max(widthCells * heightCells, 0)];
        for (int j = 0; j < heightCells; j++) {
            for (int i = 0; i < widthCells; i++) {
                cells[i + j * widthCells] = new PopManCollisionCell(minCellX + i, minCellY + j);
            }
        }
        resident.clear();
    }

    // --- reads --------------------------------------------------------------

    /** Outside the world is solid; inside, touching loads the cell so the answer is never stale. */
    @Override
    public int squareFlags(int squareX, int squareY) {
        PopManCollisionCell cell = cellForSquare(squareX, squareY);
        if (cell == null) {
            return PopManMap.BIT_SOLID;
        }
        touch(cell);
        if (!cell.loaded) {
            return 0;
        }
        return cell.flags(
                squareX - cell.cellX * PopManGeometry.SQUARES_PER_CELL,
                squareY - cell.cellY * PopManGeometry.SQUARES_PER_CELL);
    }

    @Override
    public boolean isMoveBlocked(int fromX, int fromY, int toX, int toY) {
        return PopManMap.isMoveBlocked(this::squareFlags, fromX, fromY, toX, toY);
    }

    @Override
    public int densityByte(int chunkX, int chunkY) {
        return density.densityByte(chunkX, chunkY);
    }

    /**
     * A chunk's record state (0..5, 2 = explicit) after touching its cell; -1 outside the world.
     */
    public int chunkState(int chunkX, int chunkY) {
        PopManCollisionCell cell =
                cellForSquare(
                        chunkX * PopManGeometry.SQUARES_PER_CHUNK,
                        chunkY * PopManGeometry.SQUARES_PER_CHUNK);
        if (cell == null) {
            return -1;
        }
        touch(cell);
        return cell.state(localChunkIndex(cell, chunkX, chunkY));
    }

    // --- live updates -------------------------------------------------------

    /**
     * {@code n_update}'s chunk record: a chunk's 64 fresh bytes; dirty only if anything changed.
     */
    public void applyChunk(int chunkX, int chunkY, byte[] squares) {
        PopManCollisionCell cell =
                cellForSquare(
                        chunkX * PopManGeometry.SQUARES_PER_CHUNK,
                        chunkY * PopManGeometry.SQUARES_PER_CHUNK);
        if (cell == null) {
            return;
        }
        touch(cell);
        if (!cell.loaded) {
            return;
        }
        if (cell.setChunk(localChunkIndex(cell, chunkX, chunkY), squares)) {
            cell.dirty = true;
        }
    }

    /** {@code n_update}'s square record; dirty only if the byte actually changed. */
    public void applySquare(int squareX, int squareY, int bits) {
        PopManCollisionCell cell = cellForSquare(squareX, squareY);
        if (cell == null) {
            return;
        }
        touch(cell);
        if (!cell.loaded) {
            return;
        }
        int old =
                cell.setSquare(
                        squareX - cell.cellX * PopManGeometry.SQUARES_PER_CELL,
                        squareY - cell.cellY * PopManGeometry.SQUARES_PER_CELL,
                        bits & 0xFF);
        if (old != (bits & 0xFF)) {
            cell.dirty = true;
        }
    }

    // --- lifecycle ----------------------------------------------------------

    /** The per-{@code n_update} eviction: the first resident cell idle for five seconds. */
    public void evictOneIdle() {
        long now = clockMs.getAsLong();
        for (int i = 0; i < resident.size(); i++) {
            PopManCollisionCell cell = resident.get(i);
            if (cell.lastTouchedMs + UPDATE_IDLE_MS < now) {
                unload(cell);
                resident.remove(i);
                return;
            }
        }
    }

    /** {@code n_save}: every dirty resident cell is marked clean, and written if it may be. */
    public void save() {
        for (PopManCollisionCell cell : resident) {
            if (cell.dirty) {
                cell.dirty = false;
                write(cell);
            }
        }
    }

    /** {@code n_stop}: unsaved changes are discarded, every cell dropped. */
    public void stop() {
        for (PopManCollisionCell cell : resident) {
            cell.dirty = false;
            unload(cell);
        }
        resident.clear();
        cells = new PopManCollisionCell[0];
        widthCells = 0;
        heightCells = 0;
    }

    public List<PopManCollisionCell> resident() {
        return Collections.unmodifiableList(resident);
    }

    int residentCells() {
        return resident.size();
    }

    boolean isResident(int cellX, int cellY) {
        for (PopManCollisionCell cell : resident) {
            if (cell.cellX == cellX && cell.cellY == cellY) {
                return true;
            }
        }
        return false;
    }

    PopManCollisionCell cellAt(int cellX, int cellY) {
        int lx = cellX - minCellX;
        int ly = cellY - minCellY;
        if (lx < 0 || ly < 0 || lx >= widthCells || ly >= heightCells) {
            return null;
        }
        return cells[lx + ly * widthCells];
    }

    // --- internals ----------------------------------------------------------

    private PopManCollisionCell cellForSquare(int squareX, int squareY) {
        int lx = squareX - minCellX * PopManGeometry.SQUARES_PER_CELL;
        if (lx < 0) {
            return null;
        }
        int cx = lx >> 8;
        if (cx >= widthCells) {
            return null;
        }
        int ly = squareY - minCellY * PopManGeometry.SQUARES_PER_CELL;
        if (ly < 0) {
            return null;
        }
        int cy = ly >> 8;
        if (cy >= heightCells) {
            return null;
        }
        return cells[cx + cy * widthCells];
    }

    private static int localChunkIndex(PopManCollisionCell cell, int chunkX, int chunkY) {
        return (chunkX - cell.cellX * PopManGeometry.CHUNKS_PER_CELL)
                + (chunkY - cell.cellY * PopManGeometry.CHUNKS_PER_CELL)
                        * PopManGeometry.CHUNKS_PER_CELL;
    }

    private void touch(PopManCollisionCell cell) {
        long now = clockMs.getAsLong();
        cell.lastTouchedMs = now;
        if (cell.loaded) {
            return;
        }
        int over = resident.size() - RESIDENT_TARGET;
        if (over > 0) {
            for (int i = 0; i < resident.size() && over > 0; ) {
                PopManCollisionCell candidate = resident.get(i);
                if (candidate.lastTouchedMs + TOUCH_IDLE_MS < now) {
                    unload(candidate);
                    resident.remove(i);
                    over--;
                } else {
                    i++;
                }
            }
        }
        load(cell);
        resident.add(cell);
    }

    /**
     * The save's own file wins outright. Failing that, one shipped file is read as-is and several
     * are merged first-map-wins; a cell described by several maps counts as having data even when
     * none of them could be read, while one described by nothing stays uniform 0 without data.
     */
    private void load(PopManCollisionCell cell) {
        cell.loaded = true;
        cell.reset();
        cell.hasData = parse(files == null ? null : files.readSaved(cell.cellX, cell.cellY), cell);
        if (cell.hasData || registry == null || !registry.hasCell(cell.cellX, cell.cellY)) {
            return;
        }
        List<String> paths = registry.paths(cell.cellX, cell.cellY);
        if (paths.isEmpty()) {
            return;
        }
        if (paths.size() == 1) {
            cell.hasData = parse(files.readShipped(paths.get(0)), cell);
            return;
        }
        byte[] scratch = new byte[PopManChunkData.SCRATCH_SIZE];
        Arrays.fill(scratch, PopManChunkData.SCRATCH_UNSET);
        for (String path : paths) {
            byte[] data = files.readShipped(path);
            if (data == null) {
                continue;
            }
            try {
                PopManChunkData.mergeInto(scratch, data);
            } catch (IOException e) {
                StormLogger.LOGGER.error("MapCollisionData: unable to read " + path, e);
            }
        }
        PopManChunkData.rebuildFrom(scratch, cell);
        cell.hasData = true;
    }

    private static boolean parse(byte[] data, PopManCollisionCell cell) {
        if (data == null) {
            return false;
        }
        try {
            PopManChunkData.parseInto(data, cell);
            return true;
        } catch (IOException e) {
            StormLogger.LOGGER.error(
                    "MapCollisionData: unable to read chunkdata for cell "
                            + cell.cellX
                            + ","
                            + cell.cellY,
                    e);
            return false;
        }
    }

    private void unload(PopManCollisionCell cell) {
        if (cell.dirty) {
            cell.dirty = false;
            write(cell);
        }
        cell.reset();
        cell.loaded = false;
    }

    private void write(PopManCollisionCell cell) {
        if (!cell.loaded || !cell.hasData || noSave.getAsBoolean() || files == null) {
            return;
        }
        try {
            files.writeSaved(cell.cellX, cell.cellY, PopManChunkData.write(cell));
        } catch (IOException e) {
            StormLogger.LOGGER.error(
                    "MapCollisionData: unable to write chunkdata for cell "
                            + cell.cellX
                            + ","
                            + cell.cellY,
                    e);
        }
    }
}
