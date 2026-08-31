package io.pzstorm.storm.popman;

import java.util.ArrayList;
import java.util.List;

/**
 * What {@code MapCollisionData.init} tells the DLL about the metagrid before the collision map
 * starts: per cell, the shipped {@code chunkdata} file paths and the 32x32 zombie-density bytes
 * ({@code n_initMetaGrid}, {@code n_initMetaCell}, {@code n_initMetaChunk}).
 *
 * <p>A cell only exists once one of the two per-cell calls names it. That distinction reaches the
 * population: a cell with no entry has no density at all, while a cell that exists — even with
 * every density byte at zero — takes the uniform sandbox population under {@code Distribution 2}.
 */
public final class PopManMetaRegistry implements PopManCollisionGrid.DensitySource {

    private static final class MetaCell {
        final byte[] density = new byte[PopManGeometry.CHUNKS_PER_CELL_TOTAL];
        final List<String> paths = new ArrayList<>();
    }

    private MetaCell[] cells = new MetaCell[0];
    private int minCellX;
    private int minCellY;
    private int widthCells;
    private int heightCells;

    public void initGrid(int minCellX, int minCellY, int widthCells, int heightCells) {
        this.minCellX = minCellX;
        this.minCellY = minCellY;
        this.widthCells = widthCells;
        this.heightCells = heightCells;
        this.cells = new MetaCell[Math.max(widthCells * heightCells, 0)];
    }

    public void initCell(int cellX, int cellY, String path) {
        if (path == null) {
            return;
        }
        MetaCell cell = getOrCreate(cellX, cellY);
        if (cell != null) {
            cell.paths.add(path);
        }
    }

    public void initChunk(int cellX, int cellY, int localChunkX, int localChunkY, int intensity) {
        MetaCell cell = getOrCreate(cellX, cellY);
        if (cell == null) {
            return;
        }
        if (localChunkX >= 0
                && localChunkX < PopManGeometry.CHUNKS_PER_CELL
                && localChunkY >= 0
                && localChunkY < PopManGeometry.CHUNKS_PER_CELL) {
            cell.density[localChunkX + localChunkY * PopManGeometry.CHUNKS_PER_CELL] =
                    (byte) intensity;
        }
    }

    public boolean hasCell(int cellX, int cellY) {
        return get(cellX, cellY) != null;
    }

    /** The shipped files registered for a cell, in registration order; empty when none. */
    public List<String> paths(int cellX, int cellY) {
        MetaCell cell = get(cellX, cellY);
        return cell == null ? List.of() : List.copyOf(cell.paths);
    }

    @Override
    public int densityByte(int chunkX, int chunkY) {
        MetaCell cell = get(PopManGeometry.cellOfChunk(chunkX), PopManGeometry.cellOfChunk(chunkY));
        if (cell == null) {
            return PopManPopulation.NO_DENSITY_DATA;
        }
        int localX = Math.floorMod(chunkX, PopManGeometry.CHUNKS_PER_CELL);
        int localY = Math.floorMod(chunkY, PopManGeometry.CHUNKS_PER_CELL);
        return cell.density[localX + localY * PopManGeometry.CHUNKS_PER_CELL] & 0xFF;
    }

    private int index(int cellX, int cellY) {
        int lx = cellX - minCellX;
        int ly = cellY - minCellY;
        if (lx < 0 || ly < 0 || lx >= widthCells || ly >= heightCells) {
            return -1;
        }
        return lx + ly * widthCells;
    }

    private MetaCell get(int cellX, int cellY) {
        int index = index(cellX, cellY);
        return index < 0 ? null : cells[index];
    }

    private MetaCell getOrCreate(int cellX, int cellY) {
        int index = index(cellX, cellY);
        if (index < 0) {
            return null;
        }
        if (cells[index] == null) {
            cells[index] = new MetaCell();
        }
        return cells[index];
    }
}
