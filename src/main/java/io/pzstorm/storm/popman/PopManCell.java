package io.pzstorm.storm.popman;

import java.util.ArrayList;
import java.util.List;

/**
 * One 256x256-square metagrid cell — native {@code Cell}, 0xf0 bytes. Owns all 1024 of its chunks
 * from the moment it is loaded, plus whichever travelling groups are currently inside it.
 *
 * <p>The three population counters are kept as running sums rather than recounted, exactly as the
 * native does, because {@link #currentPopulation()} is called for a cell and its eight neighbours
 * on every repopulation pass.
 */
public final class PopManCell {

    /** {@code repopQuotaTarget} while no respawn window is open. */
    public static final int NO_QUOTA = -1;

    public final int cellX;
    public final int cellY;

    public final PopManChunk[] chunks = new PopManChunk[PopManGeometry.CHUNKS_PER_CELL_TOTAL];

    /** Sum of every chunk's resident virtual zombies. */
    public short virtualCount;

    /** Sum of every chunk's {@link PopManChunk#basePop}. */
    public short basePopSum;

    /** Zombies the Java world is simulating here, pushed by {@code n_realZombieCount}. */
    public short realCount;

    public final List<PopManGroup> groups = new ArrayList<>();

    public float lastRepopTime;
    public float lastRedistributeTime;

    /** At most one repopulation path job may be in flight per cell. */
    public int outstandingTasks;

    public int repopQuotaTarget = NO_QUOTA;
    public int repopQuotaBase;
    public int repopQuotaProgress;

    public boolean loaded;

    /**
     * True once a loaded area, a group or a sound has claimed the cell, which is what earns it a
     * repopulation pass. A cell that is only resident because a neighbour peeked at its totals
     * stays inactive, so peeking never fans out across the map.
     */
    public boolean active;

    /** False means no {@code zpop_<cx>_<cy>.bin} existed, so the cell needs first population. */
    public boolean loadedFromDisk;

    public boolean dirty;

    /** Wall-clock guard for the neighbour lazy-load in the repopulation pass; zero means never. */
    public long lastTouchedMs;

    private final int[] chunkLoadedBits = new int[PopManGeometry.CHUNKS_PER_CELL];

    public PopManCell(int cellX, int cellY) {
        this.cellX = cellX;
        this.cellY = cellY;
        int originChunkX = cellX * PopManGeometry.CHUNKS_PER_CELL;
        int originChunkY = cellY * PopManGeometry.CHUNKS_PER_CELL;
        for (int y = 0; y < PopManGeometry.CHUNKS_PER_CELL; y++) {
            for (int x = 0; x < PopManGeometry.CHUNKS_PER_CELL; x++) {
                chunks[y * PopManGeometry.CHUNKS_PER_CELL + x] =
                        new PopManChunk(originChunkX + x, originChunkY + y);
            }
        }
    }

    public int minSquareX() {
        return cellX * PopManGeometry.SQUARES_PER_CELL;
    }

    public int minSquareY() {
        return cellY * PopManGeometry.SQUARES_PER_CELL;
    }

    public PopManChunk chunkAt(int chunkX, int chunkY) {
        return chunks[PopManGeometry.chunkIndex(chunkX, chunkY)];
    }

    public PopManChunk chunkAtSquare(int squareX, int squareY) {
        return chunks[PopManGeometry.chunkIndexOfSquare(squareX, squareY)];
    }

    public boolean isChunkStreamedIn(int chunkX, int chunkY) {
        int localX = Math.floorMod(chunkX, PopManGeometry.CHUNKS_PER_CELL);
        int localY = Math.floorMod(chunkY, PopManGeometry.CHUNKS_PER_CELL);
        return (chunkLoadedBits[localY] & (1 << localX)) != 0;
    }

    public void setChunkStreamedIn(int chunkX, int chunkY, boolean streamedIn) {
        int localX = Math.floorMod(chunkX, PopManGeometry.CHUNKS_PER_CELL);
        int localY = Math.floorMod(chunkY, PopManGeometry.CHUNKS_PER_CELL);
        if (streamedIn) {
            chunkLoadedBits[localY] |= 1 << localX;
        } else {
            chunkLoadedBits[localY] &= ~(1 << localX);
        }
    }

    /** Everything the cell is accountable for: real, virtual, and passing through. */
    public short currentPopulation() {
        int sum = realCount + virtualCount;
        for (PopManGroup group : groups) {
            sum += group.population();
        }
        return (short) sum;
    }

    /** Rebuilds the running sums from the chunks, after a load or a bulk edit. */
    public void recomputeAggregates() {
        int virtual = 0;
        int basePop = 0;
        for (PopManChunk chunk : chunks) {
            virtual += chunk.zombies.size();
            basePop += chunk.basePop;
        }
        virtualCount = (short) virtual;
        basePopSum = (short) basePop;
    }
}
