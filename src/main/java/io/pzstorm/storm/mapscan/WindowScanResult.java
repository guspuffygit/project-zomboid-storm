package io.pzstorm.storm.mapscan;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Output of {@link ReachabilityScanEngine#scan} for one window of chunks: the candidate (walkable
 * but not ground-reachable) components found, plus enough coverage data for {@link
 * MapScanCollector} to stitch verdicts across windows. Holds bitsets sized to the window — process
 * and drop it before loading the next window.
 */
public final class WindowScanResult {

    public final int minChunkX;
    public final int minChunkY;
    public final int maxChunkX;
    public final int maxChunkY;

    private final int zMin;
    private final int zMax;
    private final int width;
    private final int height;
    private final BitSet loadedChunks;
    private final BitSet candidate;
    private final List<CandidateComponent> components;

    public final int standableCount;
    public final int reachedCount;

    WindowScanResult(
            int minChunkX,
            int minChunkY,
            int maxChunkX,
            int maxChunkY,
            int zMin,
            int zMax,
            BitSet loadedChunks,
            BitSet candidate,
            List<CandidateComponent> components,
            int standableCount,
            int reachedCount) {
        this.minChunkX = minChunkX;
        this.minChunkY = minChunkY;
        this.maxChunkX = maxChunkX;
        this.maxChunkY = maxChunkY;
        this.zMin = zMin;
        this.zMax = zMax;
        this.width = (maxChunkX - minChunkX + 1) * ReachabilityScanEngine.CHUNK_DIM;
        this.height = (maxChunkY - minChunkY + 1) * ReachabilityScanEngine.CHUNK_DIM;
        this.loadedChunks = loadedChunks;
        this.candidate = candidate;
        this.components = components;
        this.standableCount = standableCount;
        this.reachedCount = reachedCount;
    }

    static WindowScanResult empty(int minChunkX, int minChunkY, int maxChunkX, int maxChunkY) {
        return new WindowScanResult(
                minChunkX,
                minChunkY,
                maxChunkX,
                maxChunkY,
                0,
                0,
                new BitSet(0),
                new BitSet(0),
                new ArrayList<>(),
                0,
                0);
    }

    public List<CandidateComponent> getComponents() {
        return components;
    }

    /**
     * True if the chunk lies in this window's rect and was resident when the window was scanned.
     */
    public boolean isChunkLoaded(int chunkX, int chunkY) {
        if (chunkX < minChunkX || chunkX > maxChunkX || chunkY < minChunkY || chunkY > maxChunkY) {
            return false;
        }
        int chunksW = maxChunkX - minChunkX + 1;
        return loadedChunks.get((chunkY - minChunkY) * chunksW + (chunkX - minChunkX));
    }

    /** True if the packed world square was standable but not ground-reachable in this window. */
    public boolean isCandidate(long packedSquare) {
        int x = SquareCoord.unpackX(packedSquare) - minChunkX * ReachabilityScanEngine.CHUNK_DIM;
        int y = SquareCoord.unpackY(packedSquare) - minChunkY * ReachabilityScanEngine.CHUNK_DIM;
        int z = SquareCoord.unpackZ(packedSquare);
        if (x < 0 || x >= width || y < 0 || y >= height || z < zMin || z > zMax) {
            return false;
        }
        return candidate.get(((z - zMin) * height + y) * width + x);
    }
}
