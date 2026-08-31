package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The admin-panel commands and the MPDebugInfo overlay feed, both driven through the core. */
class PopManCoreDebugTest implements UnitTest {

    private static final long NOW_MS = 5_000L;
    private static final double AGE = 100.0;

    private final PopManCore.Environment openWorld =
            new PopManCore.Environment() {
                @Override
                public PopManWorld world() {
                    return new FlagWorld();
                }

                @Override
                public Path saveDirectory() {
                    return null;
                }

                @Override
                public void requestPath(
                        int fx, int fy, int tx, int ty, PopManRepopulateTask task) {}
            };

    private PopManCore runningCore() {
        PopManCore core = new PopManCore();
        core.setClockNanos(() -> NOW_MS * 1_000_000L);
        core.setEnvironment(openWorld);
        core.init(false, true, 0, 0, 10, 10);
        // respawn off: the commands are tested in isolation from the pass they would trigger
        core.configFloat("RespawnHours", 0.0F);
        core.configFloat("RespawnUnseenHours", 16.0F);
        core.updateMain(1.0F, AGE);
        tick(core);
        return core;
    }

    private static void tick(PopManCore core) {
        core.hasDataForThread();
        core.updateThread();
        core.updateMain(1.0F, AGE);
    }

    private static PopManCell populatedCell(PopManCore core) {
        PopManCell cell = core.cells().load(1, 1, AGE, NOW_MS);
        cell.loaded = true;
        for (PopManChunk chunk : cell.chunks) {
            chunk.lastSeenTime = 90.0F;
            chunk.lastRepopTime = 80.0F;
        }
        cell.lastRepopTime = 80.0F;
        cell.chunkAt(40, 40).zombies.add(PopManZombie.spawnedAt(320, 320, () -> 0));
        cell.chunkAt(40, 40).zombies.add(PopManZombie.spawnedAt(321, 320, () -> 0));
        cell.recomputeAggregates();
        cell.realCount = 4;
        return cell;
    }

    @Test
    void spawnTimeToZeroForgetsEveryClock() {
        PopManCore core = runningCore();
        PopManCell cell = populatedCell(core);

        core.debugCommand(PopManInputFrame.DebugCommand.SPAWN_TIME_TO_ZERO, 1, 1);
        tick(core);

        assertEquals(0.0F, cell.lastRepopTime);
        assertEquals(0.0F, cell.chunkAt(40, 40).lastSeenTime);
        assertEquals(0.0F, cell.chunkAt(40, 40).lastRepopTime);
        assertEquals(2, cell.virtualCount, "population untouched");
    }

    @Test
    void clearZombiesDropsOnlyTheVirtualOnes() {
        PopManCore core = runningCore();
        PopManCell cell = populatedCell(core);
        short base = cell.basePopSum;

        core.debugCommand(PopManInputFrame.DebugCommand.CLEAR_ZOMBIES, 1, 1);
        tick(core);

        assertEquals(0, cell.virtualCount);
        assertEquals(0, cell.realCount);
        assertTrue(cell.chunkAt(40, 40).zombies.isEmpty());
        assertEquals(base, cell.basePopSum, "target population untouched");
        assertEquals(80.0F, cell.lastRepopTime, "clocks untouched");
    }

    @Test
    void spawnNowSetsEveryClockExactlyToItsThreshold() {
        PopManCore core = runningCore();
        PopManCell cell = populatedCell(core);

        core.debugCommand(PopManInputFrame.DebugCommand.SPAWN_NOW, 1, 1);
        tick(core);

        float age = (float) AGE;
        assertEquals(age, cell.lastRepopTime);
        assertEquals(age, cell.chunkAt(40, 40).lastRepopTime);
        assertEquals(age - 16.0F, cell.chunkAt(40, 40).lastSeenTime);
        assertEquals(2, cell.virtualCount);
    }

    @Test
    void commandsForUnloadedCellsAndUnknownTypesDoNothing() {
        PopManCore core = runningCore();
        PopManCell cell = populatedCell(core);

        core.debugCommand(PopManInputFrame.DebugCommand.CLEAR_ZOMBIES, 2, 2);
        core.debugCommand(99, 1, 1);
        tick(core);

        assertEquals(1, core.cells().size(), "must not conjure the cell it was aimed at");
        assertEquals(2, cell.virtualCount);
    }

    @Test
    void theOverlaySnapshotArrivesOnlyWhenAsked() {
        PopManCore core = runningCore();
        populatedCell(core);
        core.loadedAreas(1, new int[] {32, 32, 2, 2}, false);
        core.loadedAreas(1, new int[] {40, 40, 1, 1}, true);

        tick(core);
        assertFalse(core.hasMpDebugData());
        assertEquals(0, core.getLoadedCellsCount());

        core.requestMpDebugData();
        tick(core);
        assertTrue(core.hasMpDebugData());
        int resident = core.cells().size();
        assertEquals(resident, core.getLoadedCellsCount(), "one record per resident cell");
        assertEquals(2, core.getLoadedAreasCount());

        ByteBuffer buf = ByteBuffer.allocateDirect(PopManCore.MP_DEBUG_BUFFER_BYTES);
        assertEquals(resident, core.getLoadedCellsData(0, buf));
        boolean found = false;
        for (int i = 0; i < resident; i++) {
            short cx = buf.getShort();
            short cy = buf.getShort();
            short current = buf.getShort();
            short desired = buf.getShort();
            float lastRepop = buf.getFloat();
            if (cx == 1 && cy == 1) {
                found = true;
                assertEquals(6, current, "virtual plus real population");
                assertEquals(
                        PopManPopulation.desiredCellPopulation(core.config(), 0, AGE),
                        desired,
                        "desired population");
                assertEquals(80.0F, lastRepop);
            }
        }
        assertTrue(found, "the populated cell must be in the snapshot");

        buf.clear();
        assertEquals(2, core.getLoadedAreasData(0, buf));
        assertEquals(1, buf.get(), "player area flag");
        assertEquals(32, buf.getShort());
        assertEquals(32, buf.getShort());
        assertEquals(2, buf.getShort());
        assertEquals(2, buf.getShort());
        assertEquals(0, buf.get(), "server cell flag");
        assertEquals(40, buf.getShort());
        assertEquals(40, buf.getShort());
        assertEquals(1, buf.getShort());
        assertEquals(1, buf.getShort());

        buf.clear();
        assertEquals(1, core.getLoadedAreasData(1, buf), "offset skips the first record");
        assertEquals(0, buf.get());

        tick(core);
        assertFalse(core.hasMpDebugData(), "one snapshot per request");
    }

    @Test
    void loadedCellRecordsAreCappedPerCallLikeTheNative() {
        PopManCore core = runningCore();
        for (int i = 0; i < 100; i++) {
            PopManCell cell = core.cells().load(i % 10, i / 10, AGE, NOW_MS);
            cell.loaded = true;
        }
        core.requestMpDebugData();
        tick(core);

        int resident = core.cells().size();
        assertTrue(resident >= 100);
        assertEquals(resident, core.getLoadedCellsCount());
        ByteBuffer buf = ByteBuffer.allocateDirect(PopManCore.MP_DEBUG_BUFFER_BYTES);
        assertEquals(85, core.getLoadedCellsData(0, buf));
        assertEquals(resident - 85, core.getLoadedCellsData(85, buf));
    }

    @Test
    void aFinishedRepopulationPathBecomesAnOverlayEvent() {
        PopManCore core = runningCore();
        PopManCell cell = populatedCell(core);
        PopManRepopulateTask task = new PopManRepopulateTask(1, cell, 3);

        core.completePath(task, PopManRepopulateTask.PATH_FOUND, 330, 330);
        assertEquals(0, cell.outstandingTasks);
        tick(core);

        assertTrue(core.hasRepopEvents());
        assertEquals(1, core.getRepopEventCount());
        ByteBuffer buf = ByteBuffer.allocateDirect(PopManCore.MP_DEBUG_BUFFER_BYTES);
        assertEquals(1, core.getRepopEventData(0, buf));
        short chunkX = buf.getShort();
        short chunkY = buf.getShort();
        assertTrue(chunkX >= 32 && chunkX <= 41, "window origin chunk x: " + chunkX);
        assertTrue(chunkY >= 32 && chunkY <= 41, "window origin chunk y: " + chunkY);
        assertEquals((float) AGE, buf.getFloat());

        tick(core);
        assertFalse(core.hasRepopEvents(), "events are drained with the frame");
    }

    @Test
    void commandsAndRequestsAreInertOnceStopped() {
        PopManCore core = runningCore();
        core.stop();
        core.debugCommand(PopManInputFrame.DebugCommand.SPAWN_NOW, 1, 1);
        core.requestMpDebugData();
        assertTrue(core.handoff().input().debugCommands.isEmpty());
        assertFalse(core.handoff().input().mpDebugRequested);
    }
}
