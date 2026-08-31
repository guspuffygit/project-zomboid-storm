package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PopManPathSystemTest implements UnitTest {

    private final Map<Long, Integer> flags = new HashMap<>();

    private final PopManWorld world =
            new PopManWorld() {
                @Override
                public int squareFlags(int x, int y) {
                    return flags.getOrDefault(((long) x << 32) | (y & 0xFFFFFFFFL), 0);
                }

                @Override
                public int densityByte(int chunkX, int chunkY) {
                    return 0;
                }
            };

    private final PopManPathSystem.Terrain terrain =
            new PopManPathSystem.Terrain() {
                @Override
                public int squareFlags(int x, int y) {
                    return world.squareFlags(x, y);
                }

                @Override
                public boolean isMoveBlocked(int fromX, int fromY, int toX, int toY) {
                    return PopManMap.isMoveBlocked(world::squareFlags, fromX, fromY, toX, toY);
                }
            };

    private void solid(int x, int y) {
        flags.put(((long) x << 32) | (y & 0xFFFFFFFFL), PopManMap.BIT_SOLID);
    }

    private static int walk(PopManPathSystem path) {
        int status;
        int guard = 0;
        do {
            status = path.step();
        } while (status == PopManPathSystem.STATUS_WALKING && ++guard < 1_000_000);
        return status;
    }

    @Test
    void anOpenFieldIsCrossedAlongTheLine() {
        PopManPathSystem path = new PopManPathSystem(terrain);
        path.begin(0, 0, 10, 4);
        assertEquals(PopManPathSystem.STATUS_FOUND, walk(path));
        assertEquals(10, path.nodeCount(), "one node per square left behind");
        assertEquals(0, path.nodeX(0));
        assertEquals(0, path.nodeY(0));
        assertEquals(9, path.nodeX(9));
        assertEquals(10, path.currentX());
        assertEquals(4, path.currentY());
    }

    @Test
    void yMajorLinesStepInY() {
        PopManPathSystem path = new PopManPathSystem(terrain);
        path.begin(0, 0, 2, 8);
        assertEquals(PopManPathSystem.STATUS_FOUND, walk(path));
        assertEquals(8, path.nodeCount());
        assertEquals(1, path.nodeY(1));
    }

    @Test
    void startingOnTheEndIsFoundAtOnce() {
        PopManPathSystem path = new PopManPathSystem(terrain);
        path.begin(3, 3, 3, 3);
        assertEquals(PopManPathSystem.STATUS_FOUND, path.step());
        assertEquals(0, path.nodeCount());
    }

    @Test
    void aBlockedStartIsStuck() {
        solid(1, 1);
        PopManPathSystem path = new PopManPathSystem(terrain);
        path.begin(1, 1, 5, 1);
        assertEquals(PopManPathSystem.STATUS_STUCK, path.step());
        assertFalse(path.isStuck(), "the flag the native never sets stays clear");
    }

    @Test
    void aWallAcrossTheLineIsFollowedAroundItsEnd() {
        for (int y = -3; y <= 3; y++) {
            solid(5, y);
        }
        PopManPathSystem path = new PopManPathSystem(terrain);
        path.begin(0, 0, 10, 0);
        assertEquals(PopManPathSystem.STATUS_FOUND, walk(path));
        assertEquals(10, path.currentX());
        assertEquals(0, path.currentY());
        boolean wentAround = false;
        for (int i = 0; i < path.nodeCount(); i++) {
            assertFalse(path.nodeX(i) == 5 && Math.abs(path.nodeY(i)) <= 3, "never walks through");
            if (path.nodeX(i) == 5) {
                wentAround = true;
            }
        }
        assertTrue(wentAround);
    }

    @Test
    void aSealedTargetFailsInsteadOfLoopingForever() {
        for (int d = -1; d <= 1; d++) {
            solid(10 + d, 4);
            solid(10 + d, 6);
            solid(9, 5);
            solid(11, 5);
        }
        PopManPathSystem path = new PopManPathSystem(terrain);
        path.begin(0, 5, 10, 5);
        int status = walk(path);
        assertTrue(
                status == PopManPathSystem.STATUS_FAILED || status == PopManPathSystem.STATUS_STUCK,
                "status " + status);
        assertTrue(path.nodeCount() < PopManPathSystem.MAX_NODES);
    }

    @Test
    void beginKeepsTheLapCountersButClearsTheWalk() {
        PopManPathSystem path = new PopManPathSystem(terrain);
        path.begin(0, 0, 3, 0);
        walk(path);
        path.begin(0, 0, 0, 3);
        assertEquals(0, path.nodeCount());
        assertEquals(0, path.currentX());
        assertEquals(0, path.currentY());
        assertEquals(PopManPathSystem.STATUS_FOUND, walk(path));
    }
}
