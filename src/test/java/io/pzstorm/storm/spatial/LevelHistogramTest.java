package io.pzstorm.storm.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LevelHistogramTest {

    @Test
    void sumNearCoversInclusiveBandAndNothingElse() {
        LevelHistogram h = new LevelHistogram();
        h.add(0);
        h.add(1);
        h.add(1);
        h.add(2);
        h.add(-1);
        h.add(5);
        assertEquals(4, h.sumNear(0, 1));
        assertEquals(4, h.sumNear(1, 1));
        assertEquals(6, h.total());
        assertEquals(1, h.sumNear(5, 0));
        assertEquals(0, h.sumNear(3, 0));
    }

    @Test
    void outOfRangeLevelsClampInsteadOfThrowing() {
        LevelHistogram h = new LevelHistogram();
        h.add(1_000);
        h.add(-1_000);
        assertEquals(1, h.sumNear(LevelHistogram.MAX_LEVEL, 0));
        assertEquals(1, h.sumNear(LevelHistogram.MIN_LEVEL, 1));
        assertEquals(2, h.sumNear(LevelHistogram.MIN_LEVEL, 500));
    }

    @Test
    void levelOfFloorsFractionalZ() {
        assertEquals(0, LevelHistogram.levelOf(0.0F));
        assertEquals(0, LevelHistogram.levelOf(0.75F));
        assertEquals(1, LevelHistogram.levelOf(1.0F));
        assertEquals(-1, LevelHistogram.levelOf(-0.25F));
    }

    @Test
    void clearResetsEverything() {
        LevelHistogram h = new LevelHistogram();
        h.add(3);
        h.clear();
        assertEquals(0, h.total());
        assertEquals(0, h.sumNear(3, 2));
    }
}
