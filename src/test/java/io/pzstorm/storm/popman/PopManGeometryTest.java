package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

class PopManGeometryTest implements UnitTest {

    @Test
    void squaresWestOfTheOriginBelongToNegativeChunks() {
        assertEquals(0, PopManGeometry.chunkOfSquare(0));
        assertEquals(0, PopManGeometry.chunkOfSquare(7));
        assertEquals(-1, PopManGeometry.chunkOfSquare(-1));
        assertEquals(-1, PopManGeometry.chunkOfSquare(-8));
        assertEquals(-2, PopManGeometry.chunkOfSquare(-9));

        assertNotEquals(
                PopManGeometry.chunkOfSquare(-1),
                PopManGeometry.chunkOfSquare(0),
                "truncating division would fold these together and double one chunk's population");
    }

    @Test
    void cellsAreTwoHundredAndFiftySixSquaresAcross() {
        assertEquals(256, PopManGeometry.SQUARES_PER_CELL);
        assertEquals(1024, PopManGeometry.CHUNKS_PER_CELL_TOTAL);
        assertEquals(0, PopManGeometry.cellOfSquare(255));
        assertEquals(1, PopManGeometry.cellOfSquare(256));
        assertEquals(-1, PopManGeometry.cellOfSquare(-1));
        assertEquals(-1, PopManGeometry.cellOfChunk(-1));
        assertEquals(-1, PopManGeometry.cellOfChunk(-32));
        assertEquals(-2, PopManGeometry.cellOfChunk(-33));
    }

    @Test
    void everyChunkOfACellGetsItsOwnIndexIncludingNegativeCells() {
        boolean[] seen = new boolean[PopManGeometry.CHUNKS_PER_CELL_TOTAL];
        for (int y = -32; y < 0; y++) {
            for (int x = -32; x < 0; x++) {
                int index = PopManGeometry.chunkIndex(x, y);
                assertEquals(false, seen[index], "chunk " + x + "," + y + " collided");
                seen[index] = true;
            }
        }
    }

    @Test
    void squareAndChunkIndexingAgree() {
        assertEquals(PopManGeometry.chunkIndex(-1, -1), PopManGeometry.chunkIndexOfSquare(-1, -1));
        assertEquals(
                PopManGeometry.chunkIndex(4, 9),
                PopManGeometry.chunkIndexOfSquare(4 * 8 + 3, 9 * 8));
    }
}
