package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

class PopManSpawnSourceTest implements UnitTest {

    private final ScriptedWorld world = new ScriptedWorld();
    private final PopManSpawnSource source = new PopManSpawnSource(world);
    private final int[] out = new int[2];

    PopManSpawnSourceTest() {
        world.setWorldBounds(0, 0, 2, 3);
    }

    @Test
    void withNoOriginsZombiesWalkInOffTheEdgeOfTheWorld() {
        world.roll(0, 100);

        assertTrue(source.pick(out));
        assertArrayEquals(new int[] {0, 100}, out, "side 0 is the west edge");
    }

    @Test
    void eachEdgeOfTheWorldIsReachable() {
        world.setWorldBounds(-1, -2, 2, 3);

        world.roll(1, 300);
        assertTrue(source.pick(out));
        assertArrayEquals(new int[] {-256 + 300, -512}, out, "side 1 is the north edge");

        world.rolls.clear();
        world.roll(2, 40);
        assertTrue(source.pick(out));
        assertArrayEquals(new int[] {-256 + 512 - 1, -512 + 40}, out, "side 2 is the east edge");

        world.rolls.clear();
        world.roll(3, 40);
        assertTrue(source.pick(out));
        assertArrayEquals(new int[] {-256 + 40, -512 + 768 - 1}, out, "side 3 is the south edge");
    }

    @Test
    void aDesignatedRectangleIsMeasuredInSquaresNotChunks() {
        source.add(100, 200, 10, 20);
        world.roll(0, 3, 7);

        assertTrue(source.pick(out));
        assertArrayEquals(new int[] {103, 207}, out);
    }

    @Test
    void rectanglesAreHalfOpen() {
        source.add(100, 200, 10, 20);
        world.roll(0, 9, 19);

        assertTrue(source.pick(out));
        assertArrayEquals(new int[] {109, 219}, out, "the last square inside is width - 1");
    }

    @Test
    void oneRectangleIsChosenFromTheSet() {
        source.add(100, 100, 1, 1);
        source.add(500, 500, 1, 1);
        world.roll(1, 0, 0);

        assertTrue(source.pick(out));
        assertArrayEquals(new int[] {500, 500}, out);
        assertEquals(2, source.count());
    }

    @Test
    void anUnusableMapIsGivenUpOnRatherThanSearchedForever() {
        world.everySquareSpawnable = false;

        assertFalse(source.pick(out));
        assertEquals(
                PopManSpawnSource.TRIES * 2,
                world.rollsTaken,
                "a fixed hundred attempts, two draws each");
    }

    @Test
    void aFailedSearchLeavesItsLastRejectedCandidateBehind() {
        world.everySquareSpawnable = false;
        source.add(700, 800, 1, 1);

        assertFalse(source.pick(out));
        assertArrayEquals(
                new int[] {700, 800},
                out,
                "the buffer is written on every attempt, so only the return value says no");
    }

    @Test
    void clearingTheOriginsFallsBackToTheWorldEdge() {
        source.add(100, 200, 10, 20);
        source.clear();
        world.roll(0, 5);

        assertTrue(source.pick(out));
        assertEquals(0, out[0]);
        assertEquals(0, source.count());
    }

    @Test
    void anInvalidCandidateIsRetriedRatherThanReturned() {
        source.add(100, 200, 2, 1);
        world.blockedSquares.add(((long) 100 << 32) | (200 & 0xFFFFFFFFL));
        world.roll(0, 0, 0, 0, 1, 0);

        assertTrue(source.pick(out));
        assertArrayEquals(new int[] {101, 200}, out);
    }
}
