package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

class PopManLoadedAreasTest implements UnitTest {

    @Test
    void rectanglesArriveInChunksButAreQueriedInSquares() {
        PopManLoadedAreas areas = new PopManLoadedAreas();
        areas.set(new int[] {2, 3, 1, 1}, 1);

        assertTrue(areas.containsSquare(16, 24), "the chunk's first square");
        assertTrue(areas.containsSquare(23, 31), "and its last");
        assertFalse(areas.containsSquare(24, 24), "one square past the east edge");
        assertFalse(areas.containsSquare(15, 24), "one square before the west edge");
    }

    @Test
    void aRectangleIsHalfOpenInChunks() {
        PopManLoadedAreas areas = new PopManLoadedAreas();
        areas.set(new int[] {0, 0, 2, 2}, 1);

        assertTrue(areas.containsSquare(15, 15));
        assertFalse(areas.containsSquare(16, 15), "width 2 covers chunks 0 and 1, not 2");
    }

    @Test
    void negativeChunksWorkTheSameWay() {
        PopManLoadedAreas areas = new PopManLoadedAreas();
        areas.set(new int[] {-1, -1, 1, 1}, 1);

        assertTrue(areas.containsSquare(-8, -8));
        assertTrue(areas.containsSquare(-1, -1));
        assertFalse(areas.containsSquare(0, 0));
    }

    @Test
    void anySingleRectangleIsEnoughToCoverASquare() {
        PopManLoadedAreas areas = new PopManLoadedAreas();
        areas.set(new int[] {0, 0, 1, 1, 10, 10, 1, 1}, 2);

        assertEquals(2, areas.count());
        assertTrue(areas.containsSquare(4, 4));
        assertTrue(areas.containsSquare(84, 84));
        assertFalse(areas.containsSquare(40, 40));
    }

    @Test
    void surplusRectanglesAreDroppedRatherThanGrowingTheList() {
        PopManLoadedAreas areas = new PopManLoadedAreas();
        int[] packed = new int[(PopManLoadedAreas.MAX_AREAS + 10) * 4];
        for (int i = 0; i < PopManLoadedAreas.MAX_AREAS + 10; i++) {
            packed[i * 4] = i;
            packed[i * 4 + 2] = 1;
            packed[i * 4 + 3] = 1;
        }
        areas.set(packed, PopManLoadedAreas.MAX_AREAS + 10);

        assertEquals(PopManLoadedAreas.MAX_AREAS, areas.count());
    }

    @Test
    void clearingLeavesNothingCovered() {
        PopManLoadedAreas areas = new PopManLoadedAreas();
        areas.set(new int[] {0, 0, 4, 4}, 1);
        areas.clear();

        assertEquals(0, areas.count());
        assertFalse(areas.containsSquare(0, 0));
    }

    @Test
    void anEmptyUpdateEmptiesTheList() {
        PopManLoadedAreas areas = new PopManLoadedAreas();
        areas.set(new int[] {0, 0, 4, 4}, 1);
        areas.set(new int[0], 0);

        assertEquals(0, areas.count());
    }

    @Test
    void theRingIsOneSquareThickEvenThoughTheBoxIsMeasuredInChunks() {
        PopManLoadedAreas areas = new PopManLoadedAreas();
        areas.set(new int[] {0, 0, 2, 2}, 1);

        assertTrue(areas.isOnPerimeterSquare(0, 0));
        assertTrue(areas.isOnPerimeterSquare(15, 15));
        assertTrue(areas.isOnPerimeterSquare(7, 0), "the top edge, mid-chunk");
        assertTrue(areas.isOnPerimeterSquare(0, 7));
        assertFalse(areas.isOnPerimeterSquare(1, 1), "one square in is already inside");
        assertFalse(areas.isOnPerimeterSquare(8, 8), "a chunk boundary is not an edge");
    }

    @Test
    void aSquareOutsideEveryBoxIsOnNoRing() {
        PopManLoadedAreas areas = new PopManLoadedAreas();
        areas.set(new int[] {0, 0, 2, 2}, 1);

        assertFalse(areas.isOnPerimeterSquare(16, 0), "just past the half-open edge");
        assertFalse(areas.isOnPerimeterSquare(-1, 0));
    }

    @Test
    void aSquareOnTheRingOfAnyBoxCounts() {
        PopManLoadedAreas areas = new PopManLoadedAreas();
        areas.set(new int[] {0, 0, 1, 1, 10, 10, 1, 1}, 2);

        assertTrue(areas.isOnPerimeterSquare(80, 87));
        assertFalse(areas.isOnPerimeterSquare(81, 81));
    }

    @Test
    void anEmptySetHasNoRing() {
        assertFalse(new PopManLoadedAreas().isOnPerimeterSquare(0, 0));
    }
}
