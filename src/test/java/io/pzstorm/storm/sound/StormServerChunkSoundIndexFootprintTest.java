package io.pzstorm.storm.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.sound.StormServerChunkSoundIndex.Footprint;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link Footprint}'s chunk rectangle must reproduce the vanilla client formula from {@code
 * WorldSoundManager.addSound} exactly — min inclusive via int division, max exclusive via {@code
 * ceil} over float — because the index's remove/diff passes assume the rectangle covers precisely
 * the chunks the add pass wrote to. A one-chunk drift leaves stale sounds in chunk lists (never
 * aged server-side) or strips live ones.
 */
class StormServerChunkSoundIndexFootprintTest implements UnitTest {

    @Test
    void boundsMatchVanillaClientFormula() {
        int[] coords = {0, 1, 7, 8, 9, 63, 64, 100, 12345};
        int[] radii = {0, 1, 7, 8, 12, 20, 100};
        for (int x : coords) {
            for (int y : coords) {
                for (int radius : radii) {
                    Footprint fp = new Footprint(x, y, radius, 0L);
                    assertEquals(
                            (x - radius) / 8, fp.chunkMinX, "minX for x=" + x + " r=" + radius);
                    assertEquals(
                            (y - radius) / 8, fp.chunkMinY, "minY for y=" + y + " r=" + radius);
                    assertEquals(
                            (int) Math.ceil(((float) x + radius) / 8.0F),
                            fp.chunkMaxX,
                            "maxX for x=" + x + " r=" + radius);
                    assertEquals(
                            (int) Math.ceil(((float) y + radius) / 8.0F),
                            fp.chunkMaxY,
                            "maxY for y=" + y + " r=" + radius);
                }
            }
        }
    }

    @Test
    void containsChunkAcceptsExactlyTheVanillaLoopIterationSpace() {
        Footprint fp = new Footprint(100, 60, 20, 0L);
        Set<Long> vanillaLoop = new HashSet<>();
        for (int xx = fp.chunkMinX; xx < fp.chunkMaxX; xx++) {
            for (int yy = fp.chunkMinY; yy < fp.chunkMaxY; yy++) {
                vanillaLoop.add(((long) xx << 32) | (yy & 0xFFFFFFFFL));
            }
        }
        for (int xx = fp.chunkMinX - 2; xx <= fp.chunkMaxX + 2; xx++) {
            for (int yy = fp.chunkMinY - 2; yy <= fp.chunkMaxY + 2; yy++) {
                long key = ((long) xx << 32) | (yy & 0xFFFFFFFFL);
                assertEquals(
                        vanillaLoop.contains(key),
                        fp.containsChunk(xx, yy),
                        "containsChunk(" + xx + "," + yy + ")");
            }
        }
    }

    @Test
    void sameRectMeansIdenticalChunkSet() {
        // Radius growth that stays inside the same 8-tile chunk boundaries → same rect: the
        // refreshFootprint fast path may skip all list churn.
        Footprint base = new Footprint(100, 100, 9, 0L);
        Footprint grownWithinChunks = new Footprint(100, 100, 11, 5L);
        assertTrue(base.sameRect(grownWithinChunks));
        assertTrue(grownWithinChunks.sameRect(base));

        // Crossing a chunk boundary must report a different rect.
        Footprint crossed = new Footprint(100, 100, 13, 0L);
        assertFalse(base.sameRect(crossed));

        // Moving the center by a full chunk shifts the rect even at equal radius.
        Footprint moved = new Footprint(108, 100, 9, 0L);
        assertFalse(base.sameRect(moved));
    }
}
