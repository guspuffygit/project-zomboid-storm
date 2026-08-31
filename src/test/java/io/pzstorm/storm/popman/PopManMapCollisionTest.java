package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PopManMapCollisionTest implements UnitTest {

    private long nowMs = 5_000L;

    private final PopManChunkDataSource noFiles =
            new PopManChunkDataSource() {
                @Override
                public byte[] readSaved(int cellX, int cellY) {
                    return null;
                }

                @Override
                public byte[] readShipped(String path) {
                    return null;
                }

                @Override
                public void writeSaved(int cellX, int cellY, byte[] data) {}
            };

    private PopManMapCollision collision() {
        PopManCollisionGrid grid = new PopManCollisionGrid(() -> nowMs);
        PopManMetaRegistry registry = new PopManMetaRegistry();
        registry.initGrid(0, 0, 10, 10);
        grid.init(noFiles, registry, () -> false, 0, 0, 10, 10);
        return new PopManMapCollision(grid, () -> nowMs);
    }

    @Test
    void batchesCrossToTheWorkerOnlyThroughHasDataForThread() {
        PopManMapCollision mcd = collision();
        assertFalse(mcd.hasDataForThread(), "nothing queued");

        byte[] squares = new byte[64];
        squares[0] = PopManMap.BIT_SOLID;
        mcd.chunkUpdateTask(2, 2, squares);
        mcd.update();
        assertEquals(0, mcd.grid().squareFlags(16, 16), "not handed over yet");

        assertTrue(mcd.hasDataForThread());
        assertFalse(mcd.hasDataForThread(), "batch moved, a fresh one is empty");
        assertFalse(mcd.shouldWait(), "a queued batch keeps the thread awake");
        mcd.update();
        assertEquals(PopManMap.BIT_SOLID, mcd.grid().squareFlags(16, 16));
        assertTrue(mcd.shouldWait());
    }

    @Test
    void squareRecordsAreBigEndianFromTheBufferStart() {
        PopManMapCollision mcd = collision();
        ByteBuffer buffer = ByteBuffer.allocateDirect(1024);
        buffer.putInt(7).putInt(9).put((byte) PopManMap.BIT_WATER);
        buffer.putInt(8).putInt(9).put((byte) PopManMap.BIT_ROOM);
        mcd.squareUpdateTask(2, buffer);
        mcd.hasDataForThread();
        mcd.update();
        assertEquals(PopManMap.BIT_WATER, mcd.grid().squareFlags(7, 9));
        assertEquals(PopManMap.BIT_ROOM, mcd.grid().squareFlags(8, 9));
    }

    @Test
    void argumentChecksMatchTheNativeMessages() {
        PopManMapCollision mcd = collision();
        assertEquals(
                "data.length < 10 * 10",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> mcd.chunkUpdateTask(0, 0, new byte[63]))
                        .getMessage());
        assertEquals(
                "invalid count",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> mcd.squareUpdateTask(-1, ByteBuffer.allocate(9)))
                        .getMessage());
        assertThrows(
                BufferUnderflowException.class,
                () -> mcd.squareUpdateTask(2, ByteBuffer.allocate(9)));
        assertEquals(
                "curXY.length < 2",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> mcd.pathTask(0, 0, 1, 1, new int[1]))
                        .getMessage());
        assertThrows(NullPointerException.class, () -> mcd.pathTask(0, 0, 1, 1, null));
    }

    @Test
    void pathTaskWalksTheWholePathAndLeavesTheArrayAlone() {
        PopManMapCollision mcd = collision();
        int[] curXY = {123, 456};
        assertEquals(PopManPathSystem.STATUS_FOUND, mcd.pathTask(1, 1, 9, 3, curXY));
        assertArrayEquals(new int[] {123, 456}, curXY);
        assertEquals(9, mcd.pathSystem().currentX());
    }

    @Test
    void requestsAreServedLastInFirstOutWithTheReachedSquare() {
        PopManMapCollision mcd = collision();
        List<String> results = new ArrayList<>();
        mcd.requestPath(0, 0, 4, 0, (s, x, y) -> results.add("first:" + s + ":" + x + "," + y));
        mcd.requestPath(0, 0, 0, 4, (s, x, y) -> results.add("second:" + s + ":" + x + "," + y));
        assertFalse(mcd.shouldWait());

        mcd.update();
        assertEquals(List.of("second:3:0,4"), results);
        assertEquals(1, mcd.pendingPaths());
        mcd.update();
        assertEquals(List.of("second:3:0,4", "first:3:4,0"), results);
        assertTrue(mcd.shouldWait());
    }

    @Test
    void aLongWalkYieldsAfterItsTimeBudgetAndResumes() {
        PopManMapCollision mcd = collision();
        List<Integer> results = new ArrayList<>();
        mcd.requestPath(0, 0, 2000, 0, (s, x, y) -> results.add(s));
        long[] tick = {0};
        PopManMapCollision budgeted =
                new PopManMapCollision(
                        mcd.grid(),
                        () -> {
                            tick[0]++;
                            return tick[0] * PopManMapCollision.PATH_BUDGET_MS;
                        });
        budgeted.requestPath(0, 0, 2000, 0, (s, x, y) -> results.add(s));
        budgeted.update();
        assertTrue(results.isEmpty(), "yielded mid-walk");
        assertTrue(budgeted.hasActivePath());
        assertFalse(budgeted.shouldWait());
        for (int i = 0; i < 100 && results.isEmpty(); i++) {
            budgeted.update();
        }
        assertEquals(List.of(PopManPathSystem.STATUS_FOUND), results);
    }

    @Test
    void stopDropsPendingRequestsWithoutTheirCallback() {
        PopManMapCollision mcd = collision();
        List<Integer> results = new ArrayList<>();
        mcd.requestPath(0, 0, 4, 0, (s, x, y) -> results.add(s));
        mcd.chunkUpdateTask(0, 0, new byte[64]);
        mcd.hasDataForThread();
        mcd.stop();
        assertTrue(mcd.shouldWait());
        assertEquals(0, mcd.pendingPaths());
        mcd.update();
        assertTrue(results.isEmpty());
    }
}
