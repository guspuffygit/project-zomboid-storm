package io.pzstorm.storm.popman;

/**
 * The fixed grid the population is expressed in: squares nest in 8x8 chunks, chunks nest in 32x32
 * cells.
 *
 * <p>Every conversion here floors rather than truncates. World coordinates go negative west and
 * north of the origin, where truncating division would fold square -1 into chunk 0 alongside square
 * 0 and silently double one chunk's population while emptying its neighbour.
 */
public final class PopManGeometry {

    public static final int SQUARES_PER_CHUNK = 8;
    public static final int CHUNKS_PER_CELL = 32;
    public static final int SQUARES_PER_CELL = SQUARES_PER_CHUNK * CHUNKS_PER_CELL;
    public static final int CHUNKS_PER_CELL_TOTAL = CHUNKS_PER_CELL * CHUNKS_PER_CELL;

    private PopManGeometry() {}

    public static int chunkOfSquare(int square) {
        return Math.floorDiv(square, SQUARES_PER_CHUNK);
    }

    public static int cellOfSquare(int square) {
        return Math.floorDiv(square, SQUARES_PER_CELL);
    }

    public static int cellOfChunk(int chunk) {
        return Math.floorDiv(chunk, CHUNKS_PER_CELL);
    }

    /** Index into a cell's flat 1024-entry chunk array, from world square coordinates. */
    public static int chunkIndexOfSquare(int squareX, int squareY) {
        return chunkIndex(chunkOfSquare(squareX), chunkOfSquare(squareY));
    }

    /** Index into a cell's flat 1024-entry chunk array, from world chunk coordinates. */
    public static int chunkIndex(int chunkX, int chunkY) {
        return Math.floorMod(chunkY, CHUNKS_PER_CELL) * CHUNKS_PER_CELL
                + Math.floorMod(chunkX, CHUNKS_PER_CELL);
    }
}
