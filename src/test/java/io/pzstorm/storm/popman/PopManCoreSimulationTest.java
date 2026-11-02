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
    }

    private static final int NO_PATH = PopManZombie.INVALID_PATH_XY;

    @Test
    void aZombieHandedBackBecomesANumberInAChunkAgain() {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);
        cell.dirty = false;

        core.addZombie(24.5F, 32.5F, 0.0F, (byte) 2, 0, 0, NO_PATH, NO_PATH, 77);
        tick(core);

        assertEquals(1, cell.chunkAt(3, 4).zombies.size());
        assertEquals(77, cell.chunkAt(3, 4).zombies.get(0).id, "the persistent id survives");
        assertEquals(1, cell.virtualCount);
        assertTrue(cell.dirty);
    }

    @Test
    void aZombieHandedBackLoadsItsCell() {
        PopManCore core = runningCore();

        core.addZombie(24.5F, 32.5F, 0.0F, (byte) 2, 0, 0, NO_PATH, NO_PATH, 0);
        tick(core);

        assertEquals(1, core.cells().size());
        assertEquals(1, core.cells().resident(0, 0).virtualCount);
    }

    @Test
    void aZombieHandedBackWithAPathTargetKeepsWalking() {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);

        core.addZombie(24.5F, 32.5F, 0.0F, (byte) 2, 0, 0, 200, 200, 0);
        tick(core);

        assertEquals(0, cell.virtualCount);
        assertEquals(1, core.groups().size());
        assertEquals(200, core.groups().get(0).leader.pathTargetX);
    }

    @Test
    void aZombieHandedBackNearAnAggroTargetTravelsAsAGroup() {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);

        core.aggroTarget(1, 100, 100);
        core.addZombie(24.5F, 32.5F, 0.0F, (byte) 2, 0, 0, NO_PATH, NO_PATH, 0);
        tick(core);

        assertEquals(0, cell.virtualCount);
        assertEquals(1, core.groups().size());
    }

    @Test
    void aRegisteredZombieIsTrackedInItsCellUntilReleased() {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);

        int id = core.registerZombie(24.5F, 32.5F, 0.0F, (byte) 2, 0, 5, 0);
        tick(core);

        PopManGroup real = core.realZombies().get(id);
        assertNotNull(real);
        assertTrue(real.isReal);
        assertSame(cell, real.cell);
        assertTrue(cell.groups.contains(real));
        assertFalse(core.groups().contains(real), "a live zombie is not a travelling horde");

        core.releaseZombie(id);
        tick(core);

        assertTrue(core.realZombies().isEmpty());
        assertFalse(cell.groups.contains(real));
        assertFalse(real.isReal);
    }

    @Test
    void realZombieUpdatesMoveTheTrackedZombieBetweenCells() {
        PopManCore core = runningCore();
        PopManCell from = core.cells().load(0, 0, 0.0, NOW_MS);
        PopManCell to = core.cells().load(1, 0, 0.0, NOW_MS);
        int id = core.registerZombie(24.5F, 32.5F, 0.0F, (byte) 2, 0, 5, 0);
        tick(core);
        from.dirty = false;

        core.updateRealZombies(1, new int[] {id, 9}, new float[] {300.5F, 32.5F, 1.0F});
        tick(core);

        PopManGroup real = core.realZombies().get(id);
        assertSame(to, real.cell);
        assertEquals(300.5F, real.leader.x);
        assertEquals(9, real.leader.stateFlags);
        assertEquals(1.0F, real.leader.z);
        assertTrue(from.dirty, "both cells change hands");
        assertTrue(to.dirty);
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
        core.groups().add(new PopManGroup(PopManZombie.spawnedAt(5, 5, () -> 0)));

        core.save();

        assertTrue(Files.exists(saveDirectory.resolve("zpop").resolve("zpop_0_0.bin")));
        assertTrue(Files.exists(saveDirectory.resolve("zpop").resolve("zpop_virtual.bin")));
        assertTrue(Files.exists(saveDirectory.resolve("zpop").resolve("zpop_ids.bin")));
        assertFalse(cell.dirty);
    }

    @Test
    void anUnchangedHordeListIsNotRewritten() {
        PopManCore core = runningCore();
        core.cells().load(0, 0, 0.0, NOW_MS).dirty = true;

        core.save();

        assertFalse(Files.exists(saveDirectory.resolve("zpop").resolve("zpop_virtual.bin")));
    }

    @Test
    void anUnchangedCellIsNotRewritten() {
        PopManCore core = runningCore();
        core.cells().load(0, 0, 0.0, NOW_MS).dirty = false;

        core.save();

        assertFalse(Files.exists(saveDirectory.resolve("zpop").resolve("zpop_0_0.bin")));
    }

    private PopManCell savedCell(int cellX, int cellY) {
        PopManCell back = new PopManCell(cellX, cellY);
        assertTrue(new PopManStore(saveDirectory, List::of).load(back));
        return back;
    }

    @Test
    void aSaveRecordsLiveZombiesWithoutAdoptingThem() {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);
        int id = core.registerZombie(24.5F, 32.5F, 0.0F, (byte) 2, 0, 5, 0);
        tick(core);

        core.save();

        List<PopManZombie> onDisk = savedCell(0, 0).chunkAt(3, 4).zombies;
        assertEquals(1, onDisk.size());
        assertEquals(id, onDisk.get(0).id);
        assertTrue(cell.chunkAt(3, 4).zombies.isEmpty(), "the live zombie is not copied in");
        assertEquals(0, cell.virtualCount);
    }

    @Test
    void aZombieInTransitIsSavedOnceAndStillArrivesOnce() {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);

        core.addZombie(24.5F, 32.5F, 0.0F, (byte) 2, 0, 0, NO_PATH, NO_PATH, 0);
        core.save();

        assertEquals(1, savedCell(0, 0).chunkAt(3, 4).zombies.size());
        assertTrue(cell.inFlightZombies.isEmpty());
        assertTrue(cell.chunkAt(3, 4).zombies.isEmpty());

        tick(core);
        assertEquals(1, cell.chunkAt(3, 4).zombies.size());
    }

    @Test
    void aZombieReleasedBeforeTheSaveIsNotWritten() {
        PopManCore core = runningCore();
        core.cells().load(0, 0, 0.0, NOW_MS);
        int id = core.registerZombie(24.5F, 32.5F, 0.0F, (byte) 2, 0, 5, 0);
        core.releaseZombie(id);

        core.save();

        assertTrue(savedCell(0, 0).chunkAt(3, 4).zombies.isEmpty());
        assertTrue(core.realZombies().isEmpty());
    }

    @Test
    void savingOneCellSkipsAnUnchangedCell() {
        PopManCore core = runningCore();
        core.cells().load(0, 0, 0.0, NOW_MS).dirty = false;

        core.saveCell(0, 0);

        assertFalse(Files.exists(saveDirectory.resolve("zpop").resolve("zpop_0_0.bin")));
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
