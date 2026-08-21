package io.pzstorm.storm.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StormChunkIndexTest implements UnitTest {

    private static final class Obj {
        final float x;
        final float y;
        final int type;

        Obj(float x, float y, int type) {
            this.x = x;
            this.y = y;
            this.type = type;
        }
    }

    private static Set<Object> collect(
            StormChunkIndex index, int cx0, int cy0, int cx1, int cy1, int mask) {
        StormObjectList out = new StormObjectList(8);
        int n = index.collectChunkRect(cx0, cy0, cx1, cy1, mask, out);
        assertEquals(n, out.size());
        Set<Object> set = new HashSet<>();
        for (int i = 0; i < out.size(); i++) {
            set.add(out.get(i));
        }
        assertEquals(n, set.size(), "no duplicates");
        return set;
    }

    @Test
    void chunkOfFloorsTowardNegativeInfinity() {
        assertEquals(0, StormChunkIndex.chunkOf(0.0F));
        assertEquals(0, StormChunkIndex.chunkOf(7.999F));
        assertEquals(1, StormChunkIndex.chunkOf(8.0F));
        assertEquals(-1, StormChunkIndex.chunkOf(-0.5F));
        assertEquals(-1, StormChunkIndex.chunkOf(-8.0F));
        assertEquals(-2, StormChunkIndex.chunkOf(-8.5F));
        assertEquals(-1, StormChunkIndex.chunkOf(-1));
        assertEquals(1234, StormChunkIndex.chunkOf(9875));
    }

    @Test
    void notReadyUntilEndTickAndStampedWithFrame() {
        StormChunkIndex index = new StormChunkIndex();
        assertFalse(index.isReady());
        index.beginTick(7L);
        assertFalse(index.isReady());
        index.add(new Object(), 1, 1, StormChunkIndex.TYPE_ZOMBIE);
        index.endTick();
        assertTrue(index.isReady());
        assertEquals(7L, index.frame());
        assertEquals(1, index.size());
        index.invalidate();
        assertFalse(index.isReady());
    }

    @Test
    void collectsByRectAndTypeMask() {
        StormChunkIndex index = new StormChunkIndex();
        index.beginTick(1L);
        Object zombieA = "zA";
        Object zombieB = "zB";
        Object player = "p";
        Object vehicle = "v";
        index.add(zombieA, 3.5F, 4.5F, StormChunkIndex.TYPE_ZOMBIE); // chunk 0,0
        index.add(zombieB, 17F, 2F, StormChunkIndex.TYPE_ZOMBIE); // chunk 2,0
        index.add(player, 9F, 9F, StormChunkIndex.TYPE_PLAYER); // chunk 1,1
        index.add(vehicle, 9F, 1F, StormChunkIndex.TYPE_VEHICLE); // chunk 1,0
        index.endTick();

        assertEquals(Set.of(zombieA), collect(index, 0, 0, 0, 0, StormChunkIndex.MASK_ALL));
        assertEquals(
                Set.of(zombieA, zombieB, player, vehicle),
                collect(index, 0, 0, 2, 1, StormChunkIndex.MASK_ALL));
        assertEquals(
                Set.of(zombieA, zombieB), collect(index, 0, 0, 2, 1, StormChunkIndex.MASK_ZOMBIE));
        assertEquals(
                Set.of(zombieA, zombieB, player),
                collect(
                        index,
                        0,
                        0,
                        2,
                        1,
                        StormChunkIndex.MASK_ALL & ~StormChunkIndex.MASK_VEHICLE));
        assertEquals(Set.of(), collect(index, 5, 5, 9, 9, StormChunkIndex.MASK_ALL));
        assertEquals(Set.of(), collect(index, 2, 1, 0, 0, StormChunkIndex.MASK_ALL));
        assertEquals(2, index.totalOf(StormChunkIndex.TYPE_ZOMBIE));
        assertEquals(1, index.totalOf(StormChunkIndex.TYPE_PLAYER));
        assertEquals(1, index.totalOf(StormChunkIndex.TYPE_VEHICLE));
        assertEquals(0, index.totalOf(StormChunkIndex.TYPE_ANIMAL));
        assertEquals(4, index.bucketCount());
    }

    @Test
    void tileRectCoversIntersectingChunks() {
        StormChunkIndex index = new StormChunkIndex();
        index.beginTick(1L);
        Object near = "near";
        Object far = "far";
        index.add(near, 15.9F, 0F, StormChunkIndex.TYPE_ZOMBIE); // chunk 1,0
        index.add(far, 24F, 0F, StormChunkIndex.TYPE_ZOMBIE); // chunk 3,0
        index.endTick();
        StormObjectList out = new StormObjectList(8);
        assertEquals(1, index.collectTileRect(0F, 0F, 10F, 0F, StormChunkIndex.MASK_ALL, out));
        assertSame(near, out.get(0));
        out.clear();
        assertEquals(2, index.collectTileRect(0F, 0F, 24F, 0F, StormChunkIndex.MASK_ALL, out));
    }

    @Test
    void beginTickWipesPreviousSnapshotAndRetainsNothingStale() {
        StormChunkIndex index = new StormChunkIndex();
        index.beginTick(1L);
        index.add("old", 0F, 0F, StormChunkIndex.TYPE_ZOMBIE);
        index.endTick();
        index.beginTick(2L);
        index.add("new", 0F, 0F, StormChunkIndex.TYPE_PLAYER);
        index.endTick();
        assertEquals(Set.of("new"), collect(index, 0, 0, 0, 0, StormChunkIndex.MASK_ALL));
        assertEquals(0, index.totalOf(StormChunkIndex.TYPE_ZOMBIE));
        assertEquals(1, index.size());
        index.beginTick(3L);
        index.endTick();
        assertEquals(0, index.size());
        assertEquals(0, index.bucketCount());
        assertEquals(Set.of(), collect(index, -5, -5, 5, 5, StormChunkIndex.MASK_ALL));
    }

    @Test
    void randomizedAgainstBruteForceAcrossGrowthAndNegativeCoordinates() {
        Random rng = new Random(42);
        StormChunkIndex index = new StormChunkIndex();
        for (int tick = 0; tick < 5; tick++) {
            int n = tick == 0 ? 20_000 : 500 + rng.nextInt(9_000);
            List<Obj> objs = new ArrayList<>(n);
            index.beginTick(tick);
            for (int i = 0; i < n; i++) {
                Obj o =
                        new Obj(
                                (rng.nextFloat() - 0.5F) * 4000F,
                                (rng.nextFloat() - 0.5F) * 4000F,
                                rng.nextInt(StormChunkIndex.NUM_TYPES));
                objs.add(o);
                index.add(o, o.x, o.y, o.type);
            }
            index.endTick();
            assertEquals(n, index.size());
            for (int q = 0; q < 200; q++) {
                int cx0 = rng.nextInt(600) - 300;
                int cy0 = rng.nextInt(600) - 300;
                int cx1 = cx0 + rng.nextInt(q % 7 == 0 ? 700 : 20);
                int cy1 = cy0 + rng.nextInt(q % 7 == 0 ? 700 : 20);
                int mask = rng.nextInt(StormChunkIndex.MASK_ALL + 1);
                Set<Object> expected = new HashSet<>();
                for (Obj o : objs) {
                    int cx = StormChunkIndex.chunkOf(o.x);
                    int cy = StormChunkIndex.chunkOf(o.y);
                    if (cx >= cx0
                            && cx <= cx1
                            && cy >= cy0
                            && cy <= cy1
                            && (mask & (1 << o.type)) != 0) {
                        expected.add(o);
                    }
                }
                assertEquals(expected, collect(index, cx0, cy0, cx1, cy1, mask));
            }
        }
    }

    @Test
    void objectListGrowsAndClearsSlots() {
        StormObjectList list = new StormObjectList(2);
        for (int i = 0; i < 100; i++) {
            list.add(i);
        }
        assertEquals(100, list.size());
        assertEquals(57, list.get(57));
        list.clear();
        assertEquals(0, list.size());
        list.add("x");
        assertEquals("x", list.get(0));
    }
}
