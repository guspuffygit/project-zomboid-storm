package io.pzstorm.storm.mapscan;

/**
 * One connected component of walkable squares that could not be reached from ground level (z=0)
 * within a single scan window. Whether it is truly sealed is decided globally by {@link
 * MapScanCollector} after stitching components across windows.
 */
public final class CandidateComponent {

    /** Member squares as {@link SquareCoord}-packed world coordinates. */
    public final long[] squares;

    /** True if any member square is at ground level (fenced yards etc. — never vetoed). */
    public final boolean hasGroundSquare;

    /**
     * Chunks adjacent to this component that the window could not see (outside the window rect, or
     * inside it but not loaded), packed as {@code (chunkX << 32) | (chunkY & 0xFFFFFFFFL)}. If any
     * of these was never scanned by another window and has lot data, the component fails open.
     */
    public final long[] contactChunks;

    CandidateComponent(long[] squares, boolean hasGroundSquare, long[] contactChunks) {
        this.squares = squares;
        this.hasGroundSquare = hasGroundSquare;
        this.contactChunks = contactChunks;
    }

    public static long packChunk(int chunkX, int chunkY) {
        return ((long) chunkX << 32) | (chunkY & 0xFFFFFFFFL);
    }

    public static int unpackChunkX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackChunkY(long packed) {
        return (int) packed;
    }
}
