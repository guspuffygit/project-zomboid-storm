package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The worker tick as a whole: what the game sends in, and what comes back out. */
class PopManCoreSimulationTest implements UnitTest {

    /** Fixed so that a cell loaded by a test is not immediately idle enough to be evicted. */
    private static final long NOW_MS = 5_000L;

    @TempDir Path saveDirectory;

    private final List<int[]> pathRequests = new ArrayList<>();

    /** Open ground with no zombies of its own, so a test's population is only what it put there. */
    private PopManCore.Environment openWorld() {
        return new PopManCore.Environment() {
            @Override
            public PopManWorld world() {
                return new PopManWorld() {
                    @Override
                    public int squareFlags(int squareX, int squareY) {
                        return 0;
                    }

                    @Override
                    public int densityByte(int chunkX, int chunkY) {
                        return 0;
                    }
                };
            }

            @Override
            public Path saveDirectory() {
                return saveDirectory;
            }

            @Override
            public void requestPath(int fx, int fy, int tx, int ty, PopManRepopulateTask task) {
                pathRequests.add(new int[] {fx, fy, tx, ty});
            }
        };
    }

    private PopManCore runningCore() {
        PopManCore core = new PopManCore();
        core.setClockNanos(() -> NOW_MS * 1_000_000L);
        core.setEnvironment(openWorld());
        core.init(false, true, 0, 0, 10, 10);
        return core;
    }

    private static void tick(PopManCore core) {
        core.hasDataForThread();
        core.updateThread();
        core.updateMain(1.0F, 0.0);
    }

    @Test
    void aCoreWithNoMapBoundTicksWithoutDoingAnything() {
        PopManCore core = new PopManCore();
        core.init(false, true, 0, 0, 10, 10);

        core.loadChunk(3, 4, true);
        tick(core);

        assertEquals(0, core.getAddZombieCount(), "a solid world can hold nobody");
        assertNotNull(core.map());
    }

    @Test
    void aChunkStreamingInHandsItsResidentsToTheGame() {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);
        cell.chunkAt(3, 4).zombies.add(PopManZombie.spawnedAt(24, 32, () -> 0));
        cell.recomputeAggregates();

        core.loadChunk(3, 4, true);
        tick(core);

        assertEquals(1, core.getAddZombieCount());
        assertEquals(0, cell.virtualCount);
        assertEquals(1, cell.realCount);
    }

    @Test
    void aZombieHandedBackBecomesANumberInAChunkAgain() {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);
        cell.realCount = 3;

        core.addZombie(24.5F, 32.5F, 0.0F, (byte) 2, 0, 0, 0, 0);
        tick(core);

        assertEquals(1, cell.chunkAt(3, 4).zombies.size());
        assertEquals(1, cell.virtualCount);
        assertEquals(2, cell.realCount);
        assertTrue(cell.dirty);
    }

    @Test
    void aZombieHandedBackInACellNobodyHasLoadedIsDropped() {
        PopManCore core = runningCore();

        core.addZombie(24.5F, 32.5F, 0.0F, (byte) 2, 0, 0, 0, 0);
        tick(core);

        assertEquals(0, core.cells().size(), "asking must not conjure a populated cell");
    }

    @Test
    void theGamesLiveZombieTallyReachesTheCellItCountedFor() {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(1, 2, 0.0, NOW_MS);

        core.realZombieCount((short) 1, new short[] {1, 2, 42});
        tick(core);

        assertEquals(42, cell.realCount);
    }

    @Test
    /**
     * The name {@code spawnHorde} is the game's, not a description: the area form places each
     * member independently and wraps every one of them in its own single-member group, so the
     * "horde" only looks like a horde because they all walk to the same place.
     */
    void aRequestedHordeArrivesAsOneGroupPerMember() {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);

        core.spawnHorde(10, 10, 8, 8, 100.0F, 120.0F, 5);
        tick(core);

        assertEquals(5, core.groups().size());
        for (PopManGroup group : core.groups()) {
            assertEquals(1, group.members.size());
            assertSame(cell, group.cell);
            assertEquals(100, group.leader.pathTargetX);
            assertEquals(120, group.leader.pathTargetY);
        }
    }

    @Test
    void theAreasPlayersAreStreamingInReachTheMap() {
        PopManCore core = runningCore();

        core.loadedAreas(1, new int[] {2, 3, 1, 1}, false);
        core.loadedAreas(1, new int[] {5, 6, 1, 1}, true);
        tick(core);

        assertTrue(core.map().loadedAreas().containsSquare(16, 24));
        assertTrue(core.map().serverCells().containsSquare(40, 48));
    }

    @Test
    void theRadarReportsEverybodyTheSimulationIsHolding() {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);
        cell.chunkAt(0, 0).zombies.add(PopManZombie.spawnedAt(1, 2, () -> 0));
        cell.recomputeAggregates();

        core.requestRadarData();
        tick(core);

        float[] xy = new float[16];
        assertEquals(1, core.getRadarZombieData(xy));
        assertEquals(1.5F, xy[0]);
        assertEquals(2.5F, xy[1]);
    }

    @Test
    void savingWritesTheChangedCellsAndTheTravellingHordes() {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);
        cell.chunkAt(0, 0).zombies.add(PopManZombie.spawnedAt(1, 2, () -> 0));
        cell.recomputeAggregates();
        cell.dirty = true;

        core.save();

        assertTrue(Files.exists(saveDirectory.resolve("zpop").resolve("zpop_0_0.bin")));
        assertTrue(Files.exists(saveDirectory.resolve("zpop").resolve("zpop_virtual.bin")));
    }

    @Test
    void anUnchangedCellIsNotRewritten() {
        PopManCore core = runningCore();
        core.cells().load(0, 0, 0.0, NOW_MS).dirty = false;

        core.save();

        assertFalse(Files.exists(saveDirectory.resolve("zpop").resolve("zpop_0_0.bin")));
    }

    /** The zombies the caller staged are exactly the ones the cell is about to lose. */
    @Test
    void savingOneCellFoldsInTheLiveZombiesStagedForIt() {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);
        cell.realCount = 1;
        PopManZombie live = PopManZombie.spawnedAt(24, 32, () -> 0);
        core.stagedRealZombies().add(live);

        core.saveCell(0, 0);

        assertTrue(cell.chunkAt(3, 4).zombies.contains(live));
        assertEquals(1, cell.virtualCount);
        assertEquals(0, cell.realCount);
        assertTrue(core.stagedRealZombies().isEmpty());
        assertTrue(Files.exists(saveDirectory.resolve("zpop").resolve("zpop_0_0.bin")));
    }

    @Test
    void aStagedZombieStandingInAnotherCellIsNotFiledUnderThisOne() {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);
        core.stagedRealZombies().add(PopManZombie.spawnedAt(300, 300, () -> 0));

        core.saveCell(0, 0);

        assertEquals(0, cell.virtualCount);
    }

    @Test
    void savingIsInertOnceTheSubsystemHasStopped() {
        PopManCore core = runningCore();
        core.cells().load(0, 0, 0.0, NOW_MS).dirty = true;
        core.stop();

        core.save();
        core.saveCell(0, 0);

        assertFalse(Files.exists(saveDirectory.resolve("zpop")));
    }
}
