package io.pzstorm.storm.popman;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Codec for {@code chunkdata_<cx>_<cy>.bin}, the collision map {@code MapCollisionData} saves and
 * the map packs ship. Transcribed from the DLL's reader (0x18000f330 / 0x18000f470), merge reader
 * (0x18000fa70) and writer (0x18000fc70).
 *
 * <p>Layout: a big-endian {@code u16} version (0 and 1 are accepted, anything else is rejected as
 * {@code unknown file version}), then 1024 chunk records in row-major order. Each record is one
 * state byte; states 0, 1, 3, 4 and 5 mean all 64 squares share the category's byte, and <em>any
 * other</em> state byte — 2 or an unknown value — is followed by 64 explicit square bytes, which
 * are re-counted on the way in so an all-canonical record collapses back to one byte.
 */
public final class PopManChunkData {

    public static final int VERSION = 1;

    static final int STATE_EXPLICIT = PopManCollisionCell.STATE_EXPLICIT;

    static final int SQUARES = PopManGeometry.SQUARES_PER_CHUNK * PopManGeometry.SQUARES_PER_CHUNK;

    /** The merge scratch: one byte per square of a cell, 0x20 meaning "no map wrote it yet". */
    static final int SCRATCH_SIZE =
            PopManGeometry.SQUARES_PER_CELL * PopManGeometry.SQUARES_PER_CELL;

    static final byte SCRATCH_UNSET = 0x20;

    private PopManChunkData() {}

    /**
     * Applies a file to a cell in place, chunk by chunk, exactly as the native did; a truncated
     * file throws after the chunks it did cover have already been written.
     */
    static void parseInto(byte[] data, PopManCollisionCell cell) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(data);
        int version = readVersion(in);
        if (version >= 2) {
            throw new IOException("unknown file version \"" + version + "\"");
        }
        byte[] squares = new byte[SQUARES];
        for (int i = 0; i < PopManGeometry.CHUNKS_PER_CELL_TOTAL; i++) {
            if (!in.hasRemaining()) {
                throw new IOException("chunkdata truncated at chunk " + i);
            }
            int state = in.get() & 0xFF;
            switch (state) {
                case 0, 1, 3, 4, 5 -> cell.setChunkUniform(i, state);
                default -> {
                    if (in.remaining() < SQUARES) {
                        throw new IOException("chunkdata truncated inside chunk " + i);
                    }
                    in.get(squares);
                    int shared = PopManCollisionCell.classify(squares);
                    if (shared == STATE_EXPLICIT) {
                        cell.setChunkExplicit(i, squares);
                    } else {
                        cell.setChunkUniform(i, shared);
                    }
                }
            }
        }
    }

    /**
     * Overlays a shipped file onto the merge scratch: every square a map covers is written only if
     * no earlier map wrote it (first map wins), and state 5 covers nothing at all.
     */
    static void mergeInto(byte[] scratch, byte[] data) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(data);
        int version = readVersion(in);
        if (version >= 2) {
            throw new IOException("unknown file version \"" + version + "\"");
        }
        byte[] squares = new byte[SQUARES];
        for (int i = 0; i < PopManGeometry.CHUNKS_PER_CELL_TOTAL; i++) {
            if (!in.hasRemaining()) {
                throw new IOException("chunkdata truncated at chunk " + i);
            }
            int state = in.get() & 0xFF;
            switch (state) {
                case 0, 1, 3, 4 -> {
                    Arrays.fill(squares, PopManCollisionCell.UNIFORM_BY_STATE[state]);
                    mergeChunk(scratch, i, squares);
                }
                case 5 -> {}
                default -> {
                    if (in.remaining() < SQUARES) {
                        throw new IOException("chunkdata truncated inside chunk " + i);
                    }
                    in.get(squares);
                    mergeChunk(scratch, i, squares);
                }
            }
        }
    }

    private static void mergeChunk(byte[] scratch, int chunkIndex, byte[] squares) {
        int baseX =
                (chunkIndex % PopManGeometry.CHUNKS_PER_CELL) * PopManGeometry.SQUARES_PER_CHUNK;
        int baseY =
                (chunkIndex / PopManGeometry.CHUNKS_PER_CELL) * PopManGeometry.SQUARES_PER_CHUNK;
        for (int ly = 0; ly < PopManGeometry.SQUARES_PER_CHUNK; ly++) {
            int row = (baseY + ly) * PopManGeometry.SQUARES_PER_CELL + baseX;
            for (int lx = 0; lx < PopManGeometry.SQUARES_PER_CHUNK; lx++) {
                if (scratch[row + lx] == SCRATCH_UNSET) {
                    scratch[row + lx] = squares[lx + ly * PopManGeometry.SQUARES_PER_CHUNK];
                }
            }
        }
    }

    /** Rebuilds every chunk of a cell from the merge scratch, collapsing what it can. */
    static void rebuildFrom(byte[] scratch, PopManCollisionCell cell) {
        byte[] squares = new byte[SQUARES];
        for (int i = 0; i < PopManGeometry.CHUNKS_PER_CELL_TOTAL; i++) {
            int baseX = (i % PopManGeometry.CHUNKS_PER_CELL) * PopManGeometry.SQUARES_PER_CHUNK;
            int baseY = (i / PopManGeometry.CHUNKS_PER_CELL) * PopManGeometry.SQUARES_PER_CHUNK;
            for (int ly = 0; ly < PopManGeometry.SQUARES_PER_CHUNK; ly++) {
                System.arraycopy(
                        scratch,
                        (baseY + ly) * PopManGeometry.SQUARES_PER_CELL + baseX,
                        squares,
                        ly * PopManGeometry.SQUARES_PER_CHUNK,
                        PopManGeometry.SQUARES_PER_CHUNK);
            }
            int shared = PopManCollisionCell.classify(squares);
            if (shared == STATE_EXPLICIT) {
                cell.setChunkExplicit(i, squares);
            } else {
                cell.setChunkUniform(i, shared);
            }
        }
    }

    /** Version 1, then one record per chunk: the state byte, plus 64 bytes when explicit. */
    static byte[] write(PopManCollisionCell cell) {
        ByteArrayOutputStream out =
                new ByteArrayOutputStream(2 + PopManGeometry.CHUNKS_PER_CELL_TOTAL);
        out.write(VERSION >> 8);
        out.write(VERSION & 0xFF);
        for (int i = 0; i < PopManGeometry.CHUNKS_PER_CELL_TOTAL; i++) {
            int state = cell.state(i);
            out.write(state);
            if (state == STATE_EXPLICIT) {
                out.write(cell.explicitSquares(i), 0, SQUARES);
            }
        }
        return out.toByteArray();
    }

    private static int readVersion(ByteBuffer in) throws IOException {
        if (in.remaining() < 2) {
            throw new IOException("chunkdata header truncated");
        }
        return in.getShort() & 0xFFFF;
    }
}
