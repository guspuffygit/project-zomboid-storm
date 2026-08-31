package io.pzstorm.storm.popman;

import java.util.Arrays;

/**
 * One 256x256-square cell of {@code MapCollisionData}'s collision map, the DLL's {@code Cell} (0x28
 * bytes): 1024 chunks that are each either one shared byte or 64 explicit ones, plus the load /
 * has-data / dirty flags and the last-touched clock the LRU uses.
 *
 * <p>Chunks are classified by category: {@code 0}, {@code 1} (solid), {@code 8} (water), {@code
 * 0x10} (room) and {@code 0x20} are the five values a chunk can share; anything else — including
 * combinations like {@code 0x11} — is category 2 and forces the chunk explicit. The file's state
 * byte is the category, which is why state 2 means "64 bytes follow".
 */
final class PopManCollisionCell {

    static final int STATE_EXPLICIT = 2;

    static final byte[] UNIFORM_BY_STATE = {0x00, 0x01, 0, 0x08, 0x10, 0x20};

    private static final int SQUARES =
            PopManGeometry.SQUARES_PER_CHUNK * PopManGeometry.SQUARES_PER_CHUNK;

    final int cellX;
    final int cellY;

    private final byte[] uniform = new byte[PopManGeometry.CHUNKS_PER_CELL_TOTAL];
    private final byte[][] explicit = new byte[PopManGeometry.CHUNKS_PER_CELL_TOTAL][];

    boolean loaded;
    boolean hasData;
    boolean dirty;
    long lastTouchedMs;

    PopManCollisionCell(int cellX, int cellY) {
        this.cellX = cellX;
        this.cellY = cellY;
    }

    static int category(int value) {
        return switch (value & 0xFF) {
            case 0x00 -> 0;
            case 0x01 -> 1;
            case 0x08 -> 3;
            case 0x10 -> 4;
            case 0x20 -> 5;
            default -> 2;
        };
    }

    /** The category all 64 squares share, or 2 when they do not. */
    static int classify(byte[] squares) {
        int first = category(squares[0]);
        if (first == 2) {
            return 2;
        }
        for (int i = 1; i < SQUARES; i++) {
            if (category(squares[i]) != first) {
                return 2;
            }
        }
        return first;
    }

    static int chunkIndex(int localSquareX, int localSquareY) {
        return (localSquareY >> 3) * PopManGeometry.CHUNKS_PER_CELL + (localSquareX >> 3);
    }

    private static int squareIndex(int localSquareX, int localSquareY) {
        return (localSquareX & 7) + (localSquareY & 7) * PopManGeometry.SQUARES_PER_CHUNK;
    }

    int state(int chunkIndex) {
        return explicit[chunkIndex] != null ? STATE_EXPLICIT : category(uniform[chunkIndex]);
    }

    int flags(int localSquareX, int localSquareY) {
        int chunk = chunkIndex(localSquareX, localSquareY);
        byte[] squares = explicit[chunk];
        if (squares != null) {
            return squares[squareIndex(localSquareX, localSquareY)] & 0xFF;
        }
        return uniform[chunk] & 0xFF;
    }

    /** The 64 bytes of an explicit chunk, or null when it is uniform. */
    byte[] explicitSquares(int chunkIndex) {
        return explicit[chunkIndex];
    }

    void setChunkUniform(int chunkIndex, int state) {
        uniform[chunkIndex] = UNIFORM_BY_STATE[state];
        explicit[chunkIndex] = null;
    }

    /** Replaces a chunk's 64 bytes verbatim, without the collapse {@link #setChunk} applies. */
    void setChunkExplicit(int chunkIndex, byte[] squares) {
        explicit[chunkIndex] = Arrays.copyOf(squares, SQUARES);
    }

    /**
     * The live chunk update: reports whether anything changed. A chunk already sharing the new
     * category is left alone; otherwise the category is stored, explicit only when it has to be.
     */
    boolean setChunk(int chunkIndex, byte[] squares) {
        int oldState = state(chunkIndex);
        int newState = classify(squares);
        if (oldState == newState && newState != STATE_EXPLICIT) {
            return false;
        }
        if (newState == STATE_EXPLICIT) {
            setChunkExplicit(chunkIndex, squares);
        } else {
            setChunkUniform(chunkIndex, newState);
        }
        return true;
    }

    /**
     * The live square update: returns the square's previous value. A square whose category does not
     * change is not written, so the caller sees "old == new" and leaves the cell clean; a real
     * change re-counts the chunk and collapses it back to one byte when all 64 agree again.
     */
    int setSquare(int localSquareX, int localSquareY, int value) {
        int chunk = chunkIndex(localSquareX, localSquareY);
        int old = flags(localSquareX, localSquareY);
        int oldCategory = category(old);
        int newCategory = category(value);
        if (oldCategory == newCategory && oldCategory != STATE_EXPLICIT) {
            return value;
        }
        byte[] squares = explicit[chunk];
        if (squares == null) {
            squares = new byte[SQUARES];
            Arrays.fill(squares, uniform[chunk]);
            explicit[chunk] = squares;
        }
        squares[squareIndex(localSquareX, localSquareY)] = (byte) value;
        int shared = classify(squares);
        if (shared != STATE_EXPLICIT) {
            setChunkUniform(chunk, shared);
        }
        return old;
    }

    /** Every chunk back to uniform 0, the state a freshly loaded cell starts in. */
    void reset() {
        Arrays.fill(uniform, (byte) 0);
        Arrays.fill(explicit, null);
    }

    boolean isExplicit(int chunkIndex) {
        return explicit[chunkIndex] != null;
    }
}
