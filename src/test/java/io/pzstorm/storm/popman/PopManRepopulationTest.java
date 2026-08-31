package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PopManRepopulationTest implements UnitTest {

    private static final double AGE = 1000.0;

    private final PopManConfig config = new PopManConfig();
    private final ScriptedWorld world = new ScriptedWorld();
    private final List<int[]> requested = new ArrayList<>();
    private PopManCellMap cells;
    private PopManRepopulation repopulation;

    private PopManRepopulation build(boolean sourceAvailable) {
        config.respawnHours = 72.0F;
        cells = new PopManCellMap(config, world, cell -> true);
        repopulation =
                new PopManRepopulation(
                        config,
                        world,
                        cells,
                        out -> {
                            out[0] = 0;
                            out[1] = 0;
                            return sourceAvailable;
                        },
                        (fromX, fromY, toX, toY, task) ->
                                requested.add(new int[] {fromX, fromY, toX, toY, task.count}));
        return repopulation;
    }

    /** A cell that is due, badly under-populated, and has one chunk worth filling. */
    private PopManCell dueCell() {
        PopManCell cell = cells.load(0, 0, AGE, 0L);
        cell.basePopSum = 1000;
        cell.lastRepopTime = 0.0F;
        cell.chunkAt(5, 5).basePop = 10;
        return cell;
    }

    @Test
    void aDueCellQueuesExactlyOnePathJob() {
        build(true);
        PopManCell cell = dueCell();

        PopManRepopulateTask task = repopulation.repopulateCell(cell, AGE, 0L);

        assertNotNull(task);
        assertEquals(1, requested.size());
        assertEquals(1, cell.outstandingTasks);
        assertEquals(40, requested.get(0)[2], "the destination is inside the candidate chunk");
        assertEquals(40, requested.get(0)[3]);
        assertEquals(AGE, cell.chunkAt(5, 5).lastRepopTime);

        assertNull(
                repopulation.repopulateCell(cell, AGE, 0L),
                "a cell with a job in flight is skipped");
        assertEquals(1, requested.size());
    }

    /**
     * At default settings the quota works out to less than one zombie, so the floor is the only
     * thing that makes repopulation happen at all.
     */
    @Test
    void theBatchFloorIsWhatActuallyDrivesRepopulation() {
        build(true);
        PopManCell cell = dueCell();

        PopManRepopulateTask task = repopulation.repopulateCell(cell, AGE, 0L);

        assertEquals(1, cell.repopQuotaBase, "a thousand-zombie cell earns one zombie per window");
        assertEquals(PopManRepopulation.MIN_BATCH, task.count);
    }

    @Test
    void aDeficitTooSmallToChaseResetsTheClockInsteadOfAccumulating() {
        build(true);
        PopManCell cell = dueCell();
        cell.realCount = 1495;
        cell.repopQuotaTarget = 7;

        assertNull(repopulation.repopulateCell(cell, AGE, 0L));
        assertEquals(PopManCell.NO_QUOTA, cell.repopQuotaTarget);
        assertEquals(AGE, cell.lastRepopTime, "the clock restarts, so no respawn debt builds up");
        assertTrue(requested.isEmpty());
    }

    @Test
    void aCellThatIsNotDueYetKeepsItsClock() {
        build(true);
        PopManCell cell = dueCell();
        cell.lastRepopTime = (float) AGE - 1.0F;

        assertNull(repopulation.repopulateCell(cell, AGE, 0L));
        assertEquals((float) AGE - 1.0F, cell.lastRepopTime);
        assertEquals(PopManCell.NO_QUOTA, cell.repopQuotaTarget);
    }

    @Test
    void zeroRespawnHoursTurnsRepopulationOff() {
        build(true);
        PopManCell cell = dueCell();
        config.respawnHours = 0.0F;

        assertNull(repopulation.repopulateCell(cell, AGE, 0L));
        assertEquals(0.0F, cell.lastRepopTime, "an off switch must not touch the clock");
    }

    @Test
    void overcrowdedNeighboursAreExpectedToWalkInInstead() {
        build(true);
        PopManCell cell = dueCell();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 || dy != 0) {
                    cells.load(dx, dy, AGE, 0L).realCount = 200;
                }
            }
        }

        assertNull(repopulation.repopulateCell(cell, AGE, 0L));
        assertEquals(1600, repopulation.neighbourSurplus(cell, AGE, 0L));
    }

    @Test
    void aChunkWithNoRoomLeftIsNotACandidate() {
        build(true);
        PopManCell cell = dueCell();
        for (int i = 0; i < 15; i++) {
            cell.chunkAt(5, 5).zombies.add(new PopManZombie());
        }

        assertNull(repopulation.repopulateCell(cell, AGE, 0L));
        assertEquals(AGE, cell.lastRepopTime, "giving up closes the window rather than retrying");
    }

    @Test
    void aChunkAPlayerIsStreamingInIsNotACandidate() {
        build(true);
        PopManCell cell = dueCell();
        world.loadRect(40, 40, 1, 1);

        assertNull(repopulation.repopulateCell(cell, AGE, 0L));
    }

    @Test
    void aRecentlySeenChunkIsNotACandidate() {
        build(true);
        PopManCell cell = dueCell();
        cell.chunkAt(5, 5).lastSeenTime = (float) AGE - 1.0F;

        assertNull(repopulation.repopulateCell(cell, AGE, 0L));
    }

    @Test
    void withNowhereToWalkInFromNoJobIsQueued() {
        build(false);
        PopManCell cell = dueCell();

        assertNull(repopulation.repopulateCell(cell, AGE, 0L));
        assertTrue(requested.isEmpty());
        assertEquals(AGE, cell.lastRepopTime);
    }

    @Test
    void aFailedPathReleasesTheCellWithoutSpawning() {
        build(true);
        PopManCell cell = dueCell();
        PopManRepopulateTask task = repopulation.repopulateCell(cell, AGE, 0L);

        repopulation.completePath(task, 0, 45, 45, AGE, 0L, new PopManResultFrame(), false);

        assertEquals(0, cell.outstandingTasks);
        assertEquals(0, cell.virtualCount);
    }

    @Test
    void aCompletedPathSpawnsItsBatchAroundTheEndpoint() {
        build(true);
        PopManCell cell = dueCell();
        PopManRepopulateTask task = repopulation.repopulateCell(cell, AGE, 0L);
        PopManResultFrame out = new PopManResultFrame();

        repopulation.completePath(
                task, PopManRepopulateTask.PATH_FOUND, 45, 45, AGE, 0L, out, true);

        assertEquals(0, cell.outstandingTasks);
        assertEquals(PopManRepopulation.MIN_BATCH, cell.virtualCount);
        assertEquals(PopManRepopulation.MIN_BATCH, cell.repopQuotaProgress);

        int placed = 0;
        for (PopManChunk chunk : cell.chunks) {
            placed += chunk.zombies.size();
        }
        assertEquals(PopManRepopulation.MIN_BATCH, placed);
        assertEquals(1, out.repopEvents.size(), "the server publishes a debug event per batch");
        assertEquals(
                3, out.repopEvents.get(0).chunkX(), "the 40-square window starts at square 24");
    }

    @Test
    void aBatchIsCappedByWhatTheFloodFillCouldReach() {
        build(true);
        PopManCell cell = dueCell();
        PopManRepopulateTask task = repopulation.repopulateCell(cell, AGE, 0L);
        world.loadRect(24, 24, 40, 40);

        repopulation.completePath(
                task,
                PopManRepopulateTask.PATH_FOUND,
                45,
                45,
                AGE,
                0L,
                new PopManResultFrame(),
                false);

        assertEquals(1, task.count, "only the endpoint square survived the fence");
        assertEquals(1, cell.virtualCount);
    }

    /**
     * The quota lands on whichever cell the path happened to end in. A requester whose path strayed
     * next door never advances its own clock — vanilla behaviour, reproduced deliberately.
     */
    @Test
    void theQuotaIsCreditedToTheCellThePathEndedIn() {
        build(true);
        PopManCell cell = dueCell();
        PopManRepopulateTask task = repopulation.repopulateCell(cell, AGE, 0L);

        repopulation.completePath(
                task,
                PopManRepopulateTask.PATH_FOUND,
                300,
                45,
                AGE,
                0L,
                new PopManResultFrame(),
                false);

        PopManCell neighbour = cells.resident(1, 0);
        assertEquals(0, cell.repopQuotaProgress, "the requester is credited nothing");
        assertEquals(PopManRepopulation.MIN_BATCH, neighbour.repopQuotaProgress);
        assertEquals(PopManRepopulation.MIN_BATCH, neighbour.virtualCount);
    }
}
