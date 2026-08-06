package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GameMemoryTest {

    private static final long GIB = 1L << 30;

    @Test
    void autoIsHalfRamPlusOneCappedAtSixteen() {
        assertEquals(0, GameMemory.autoGbFor(0));
        assertEquals(0, GameMemory.autoGbFor(-1));
        assertEquals(2, GameMemory.autoGbFor(2 * GIB));
        assertEquals(5, GameMemory.autoGbFor(8 * GIB));
        assertEquals(9, GameMemory.autoGbFor(16 * GIB));
        // the OS reports slightly under the nominal size; must still land on the nominal result
        assertEquals(9, GameMemory.autoGbFor(16 * GIB - 120_000_000L));
        assertEquals(16, GameMemory.autoGbFor(32 * GIB));
        assertEquals(16, GameMemory.autoGbFor(128 * GIB));
    }

    @Test
    void manualClampsToFourThroughThirtyTwo() {
        assertEquals(4, GameMemory.clampManualGb(0));
        assertEquals(4, GameMemory.clampManualGb(3));
        assertEquals(8, GameMemory.clampManualGb(8));
        assertEquals(32, GameMemory.clampManualGb(32));
        assertEquals(32, GameMemory.clampManualGb(33));
    }

    @Test
    void detectsRamOnThisMachine() {
        assertTrue(GameMemory.totalSystemBytes() > 0, "platform bean should expose total RAM");
        assertTrue(GameMemory.autoGb() > 0);
    }
}
