package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PopManChunkDataTest implements UnitTest {

    /** A whole-cell file where every chunk has the given state, optionally with explicit bytes. */
    static byte[] file(int version, int state, byte[] explicit) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(version >> 8);
        out.write(version & 0xFF);
        for (int i = 0; i < PopManGeometry.CHUNKS_PER_CELL_TOTAL; i++) {
            out.write(state);
            if (explicit != null) {
                out.write(explicit, 0, explicit.length);
            }
        }
        return out.toByteArray();
    }

    private static PopManCollisionCell cell() {
        return new PopManCollisionCell(0, 0);
    }

    @Test
    void uniformStatesExpandToTheirFlagByte() throws IOException {
        int[][] stateToFlags = {{0, 0x00}, {1, 0x01}, {3, 0x08}, {4, 0x10}, {5, 0x20}};
        for (int[] pair : stateToFlags) {
            PopManCollisionCell cell = cell();
            PopManChunkData.parseInto(file(PopManChunkData.VERSION, pair[0], null), cell);
            assertEquals(pair[1], cell.flags(0, 0), "state " + pair[0]);
            assertEquals(pair[1], cell.flags(255, 255), "state " + pair[0]);
            assertFalse(cell.isExplicit(0));
            assertEquals(pair[0], cell.state(0));
        }
    }

    @Test
    void explicitChunksKeepEverySquare() throws IOException {
        byte[] squares = new byte[64];
        for (int i = 0; i < 64; i++) {
            squares[i] = (byte) i;
        }
        PopManCollisionCell cell = cell();
        PopManChunkData.parseInto(file(1, PopManChunkData.STATE_EXPLICIT, squares), cell);

        assertTrue(cell.isExplicit(0));
        assertEquals(0, cell.flags(0, 0));
        assertEquals(7, cell.flags(7, 0));
        assertEquals(8, cell.flags(0, 1));
        assertEquals(63, cell.flags(7, 7));
        assertEquals(63, cell.flags(255, 255), "last square of the last chunk");
    }

    @Test
    void onlyCanonicalCategoriesCollapseWhenExplicitSquaresAgree() throws IOException {
        byte[] solid = new byte[64];
        Arrays.fill(solid, (byte) 0x01);
        PopManCollisionCell cell = cell();
        PopManChunkData.parseInto(file(1, PopManChunkData.STATE_EXPLICIT, solid), cell);
        assertFalse(cell.isExplicit(5), "all-solid collapses to state 1");
        assertEquals(1, cell.state(5));

        byte[] mixed = new byte[64];
        Arrays.fill(mixed, (byte) 0x11);
        PopManChunkData.parseInto(file(1, PopManChunkData.STATE_EXPLICIT, mixed), cell);
        assertTrue(cell.isExplicit(5), "0x11 is category 2 even when every square agrees");
        assertEquals(0x11, cell.flags(40, 0));
    }

    @Test
    void unknownStateBytesAreReadAsExplicitRecords() throws IOException {
        byte[] squares = new byte[64];
        Arrays.fill(squares, (byte) 0x08);
        PopManCollisionCell cell = cell();
        PopManChunkData.parseInto(file(1, 9, squares), cell);
        assertEquals(3, cell.state(0), "64 bytes follow, then the record is re-counted");
        assertEquals(0x08, cell.flags(255, 255));
    }

    @Test
    void versionsZeroAndOneAreAcceptedAndNothingElse() throws IOException {
        PopManChunkData.parseInto(file(0, 1, null), cell());
        PopManChunkData.parseInto(file(1, 1, null), cell());
        IOException e =
                assertThrows(
                        IOException.class,
                        () -> PopManChunkData.parseInto(file(2, 5, null), cell()));
        assertEquals("unknown file version \"2\"", e.getMessage());
    }

    @Test
    void fileSizeIsExactlyHeaderPlusRecords() {
        assertEquals(2 + 1024, file(1, 5, null).length);
        assertEquals(2 + 1024 + 64 * 1024, file(1, 2, new byte[64]).length);
    }

    @Test
    void aSecondFileOverwritesTheFirstChunkForChunk() throws IOException {
        PopManCollisionCell cell = cell();
        PopManChunkData.parseInto(file(1, 1, null), cell);
        PopManChunkData.parseInto(file(1, 5, null), cell);
        assertEquals(0x20, cell.flags(100, 100));
    }

    @Test
    void truncatedFilesThrowAfterTheChunksTheyDidCover() {
        PopManCollisionCell cell = cell();
        byte[] truncated = Arrays.copyOf(file(1, 1, null), 600);
        assertThrows(IOException.class, () -> PopManChunkData.parseInto(truncated, cell));
        assertEquals(1, cell.state(0), "chunks before the cut were applied");
        assertEquals(0, cell.state(1000), "chunks after it were not");
        assertThrows(IOException.class, () -> PopManChunkData.parseInto(new byte[1], cell()));
    }

    @Test
    void writeRoundTripsAndCollapsesUniformChunksToOneByte() throws IOException {
        PopManCollisionCell cell = cell();
        byte[] squares = new byte[64];
        squares[9] = 0x01;
        cell.setChunk(3, squares);
        cell.setChunkUniform(4, 3);

        byte[] written = PopManChunkData.write(cell);
        assertEquals(2 + 1024 + 64, written.length);
        assertEquals(0, written[0]);
        assertEquals(1, written[1], "version 1 big-endian");
        assertEquals(2, written[2 + 3]);
        assertEquals(1, written[2 + 3 + 1 + 9]);
        assertEquals(3, written[2 + 3 + 1 + 64]);

        PopManCollisionCell back = cell();
        PopManChunkData.parseInto(written, back);
        assertArrayEquals(squares, back.explicitSquares(3));
        assertEquals(0x08, back.flags(4 * 8, 0));
    }

    @Test
    void mergeIsFirstMapWinsAndStateFiveIsTransparent() throws IOException {
        byte[] scratch = new byte[PopManChunkData.SCRATCH_SIZE];
        Arrays.fill(scratch, PopManChunkData.SCRATCH_UNSET);

        PopManChunkData.mergeInto(scratch, file(1, 5, null));
        assertEquals(PopManChunkData.SCRATCH_UNSET, scratch[0], "state 5 writes nothing");

        PopManChunkData.mergeInto(scratch, file(1, 1, null));
        PopManChunkData.mergeInto(scratch, file(1, 3, null));
        assertEquals(0x01, scratch[0], "the first map to cover a square keeps it");

        PopManCollisionCell cell = cell();
        PopManChunkData.rebuildFrom(scratch, cell);
        assertEquals(1, cell.state(0));
        assertEquals(0x01, cell.flags(255, 255));
    }

    @Test
    void rebuildLeavesUncoveredSquaresAtTheScratchDefault() {
        byte[] scratch = new byte[PopManChunkData.SCRATCH_SIZE];
        Arrays.fill(scratch, PopManChunkData.SCRATCH_UNSET);
        scratch[5] = 0x01;
        PopManCollisionCell cell = cell();
        PopManChunkData.rebuildFrom(scratch, cell);
        assertTrue(cell.isExplicit(0));
        assertEquals(0x01, cell.flags(5, 0));
        assertEquals(0x20, cell.flags(6, 0));
        assertEquals(5, cell.state(1));
    }
}
