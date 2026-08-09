package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GameMemoryTest {

    private static final long GIB = 1L << 30;

    @Test
    void autoIsHalfRamPlusOneCappedAtSixteenAndByNativeHeadroom() {
        assertEquals(0, GameMemory.autoGbFor(0));
        assertEquals(0, GameMemory.autoGbFor(-1));
        // small machines keep the game's own -Xmx (3 GB) — raising it starves the native side
        assertEquals(0, GameMemory.autoGbFor(2 * GIB));
        assertEquals(0, GameMemory.autoGbFor(8 * GIB));
        assertEquals(0, GameMemory.autoGbFor(12 * GIB));
        // headroom-capped: total minus NATIVE_HEADROOM_GB beats half+1
        assertEquals(7, GameMemory.autoGbFor(16 * GIB));
        // the OS reports slightly under the nominal size; must still land on the nominal result
        assertEquals(7, GameMemory.autoGbFor(16 * GIB - 120_000_000L));
        // a 16 GB machine with an iGPU carve-out reports ~15.4 GB — the shape that used to get
        // -Xmx9g and die of native OOM
        assertEquals(6, GameMemory.autoGbFor((long) (15.4 * GIB)));
        // half+1-capped once the machine is big enough
        assertEquals(13, GameMemory.autoGbFor(24 * GIB));
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
        assertEquals(GameMemory.autoGbFor(GameMemory.totalSystemBytes()), GameMemory.autoGb());
    }
}
