package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PopManCellMapTest implements UnitTest {

    private final ScriptedWorld world = new ScriptedWorld();
    private final PopManConfig config = new PopManConfig();
    private final PopManCellMap cells = new PopManCellMap(config, world, cell -> false);

    PopManCellMapTest() {
        world.setWorldBounds(-4, -4, 8, 8);
        world.blocked = true;
    }

    @Test
    void askingForASquaresCellDoesNotConjureOne() {
        assertNull(cells.residentForSquare(0, 0));
        assertEquals(0, cells.size(), "a lookup must not populate a cell");

        cells.load(0, 0, 0, 0);
        assertNotNull(cells.residentForSquare(0, 0));
    }

    @Test
    void squaresOutsideTheWorldHaveNoCellAtAll() {
        cells.load(-100, -100, 0, 0);

        assertNull(cells.residentForSquare(-100 * 256, 0), "west of the world");
        assertNull(cells.residentForSquare(0, 100 * 256), "south of the world");
    }

    @Test
    void loadingIsIdempotent() {
        PopManCell first = cells.load(1, 1, 0, 0);

        assertSame(first, cells.load(1, 1, 5, 5));
        assertEquals(1, cells.size());
    }

    @Test
    void leavingAnAreaStartsItsGracePeriodRatherThanSkippingIt() {
        world.loadedAreas().set(new int[] {30, 0, 2, 1}, 1);
        cells.refreshSeenClocks(world.loadedAreas(), 120.0, 0, false);

        PopManCell cell = cells.resident(0, 0);
        assertEquals(120.0F, cell.chunkAt(30, 0).lastSeenTime);
        assertEquals(120.0F, cell.chunkAt(31, 0).lastSeenTime);
        assertTrue(cell.dirty);
    }

    @Test
    void chunksPastACellBoundaryKeepAStaleClock() {
        world.loadedAreas().set(new int[] {30, 0, 4, 1}, 1);
        cells.refreshSeenClocks(world.loadedAreas(), 120.0, 0, false);

        assertEquals(120.0F, cells.resident(0, 0).chunkAt(31, 0).lastSeenTime);
        assertEquals(
                0.0F,
                cells.resident(1, 0).chunkAt(32, 0).lastSeenTime,
                "vanilla clamps the window to the first cell, so this half is never refreshed");
        assertEquals(0.0F, cells.resident(1, 0).chunkAt(33, 0).lastSeenTime);
    }

    @Test
    void spanningTheWholeAreaRefreshesBothHalves() {
        world.loadedAreas().set(new int[] {30, 0, 4, 1}, 1);
        cells.refreshSeenClocks(world.loadedAreas(), 120.0, 0, true);

        assertEquals(120.0F, cells.resident(0, 0).chunkAt(31, 0).lastSeenTime);
        assertEquals(120.0F, cells.resident(1, 0).chunkAt(32, 0).lastSeenTime);
        assertEquals(120.0F, cells.resident(1, 0).chunkAt(33, 0).lastSeenTime);
    }

    @Test
    void refreshingCoversTheWholeRectangleWithinOneCell() {
        world.loadedAreas().set(new int[] {2, 3, 2, 2}, 1);
        cells.refreshSeenClocks(world.loadedAreas(), 40.0, 0, false);

        PopManCell cell = cells.resident(0, 0);
        assertEquals(40.0F, cell.chunkAt(2, 3).lastSeenTime);
        assertEquals(40.0F, cell.chunkAt(3, 4).lastSeenTime);
        assertEquals(0.0F, cell.chunkAt(4, 3).lastSeenTime, "half-open in chunks");
        assertEquals(0.0F, cell.chunkAt(2, 5).lastSeenTime);
    }

    @Test
    void anAlreadyCurrentClockDoesNotDirtyTheCell() {
        world.loadedAreas().set(new int[] {0, 0, 1, 1}, 1);
        cells.refreshSeenClocks(world.loadedAreas(), 40.0, 0, false);

        PopManCell cell = cells.resident(0, 0);
        cell.dirty = false;
        cells.refreshSeenClocks(world.loadedAreas(), 40.0, 0, false);

        assertEquals(false, cell.dirty, "a save is only worth doing when something changed");
    }

    @Test
    void negativeCellsRefreshTheirOwnChunks() {
        world.loadedAreas().set(new int[] {-2, -2, 2, 2}, 1);
        cells.refreshSeenClocks(world.loadedAreas(), 12.0, 0, false);

        PopManCell cell = cells.resident(-1, -1);
        assertEquals(12.0F, cell.chunkAt(-2, -2).lastSeenTime);
        assertEquals(12.0F, cell.chunkAt(-1, -1).lastSeenTime);
    }

    @Test
    void anIdleCellIsEvictedOnceItsGracePeriodExpires() {
        cells.load(0, 0, 0, 0);

        assertTrue(
                cells.evictIdle(PopManCellMap.IDLE_MS, Set.of()).isEmpty(),
                "the test is strictly later");
        assertEquals(1, cells.evictIdle(PopManCellMap.IDLE_MS + 1, Set.of()).size());
        assertEquals(0, cells.size());
    }

    @Test
    void aCellWithAPathJobInFlightIsNotEvicted() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        cell.outstandingTasks = 1;

        assertTrue(cells.evictIdle(PopManCellMap.IDLE_MS * 10, Set.of()).isEmpty());

        cell.outstandingTasks = 0;
        assertEquals(1, cells.evictIdle(PopManCellMap.IDLE_MS * 10, Set.of()).size());
    }

    @Test
    void aCellHostingAHordeIsNotEvicted() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        cell.groups.add(new PopManGroup(new PopManZombie()));

        assertTrue(
                cells.evictIdle(PopManCellMap.IDLE_MS * 10, Set.of()).isEmpty(),
                "the horde's members live nowhere else");
    }

    @Test
    void touchingACellRestartsItsGracePeriod() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        cell.lastTouchedMs = 5000;

        assertTrue(cells.evictIdle(6000, Set.of()).isEmpty());
        assertEquals(1, cells.evictIdle(7001, Set.of()).size());
    }
}
