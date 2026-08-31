package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class PopManCoreTest implements UnitTest {

    @Test
    void staysInertOnAnMpClient() {
        PopManCore core = new PopManCore();
        core.init(true, false, 0, 0, 30, 30);

        assertTrue(core.isStopped(), "native n_init returns immediately when isClient");
        assertEquals(0, core.getWidth());
    }

    /**
     * Seven vanilla wrappers reach their native with no {@code !GameClient.client} guard, and
     * {@code IngameState} calls {@code readyToPause()} every frame on MP clients where init never
     * ran. Those must not blow up.
     */
    @Test
    void uninitialisedCallsAreInert() {
        PopManCore core = new PopManCore();

        assertTrue(core.isStopped());
        core.updateThread();
        core.aggroTarget(1, 2, 3);
        core.spawnHorde(0, 0, 8, 8, 4.0F, 4.0F, 10);
        core.loadedAreas(0, new int[256], false);

        assertTrue(core.readyToPause());
        assertTrue(core.shouldWait());
        assertFalse(core.hasDataForThread());
        assertEquals(0, core.getAddZombieCount());
        assertTrue(core.handoff().input().isEmpty(), "nothing is queued for a worker that is off");
    }

    @Test
    void initOnServerOpensTheSubsystem() {
        PopManCore core = new PopManCore();
        core.init(false, true, 100, 200, 30, 40);

        assertFalse(core.isStopped());
        assertEquals(100, core.getMinX());
        assertEquals(200, core.getMinY());
        assertEquals(30, core.getWidth());
        assertEquals(40, core.getHeight());
    }

    @Test
    void arraySettersDefensivelyCopy() {
        PopManCore core = new PopManCore();
        int[] origins = {1, 2, 3, 4};
        String[] outfits = {"police", "doctor"};

        core.setSpawnOrigins(origins);
        core.setOutfitNames(outfits);
        origins[0] = 999;
        outfits[0] = "mutated";

        assertArrayEquals(new int[] {1, 2, 3, 4}, core.getSpawnOrigins());
        assertArrayEquals(new String[] {"police", "doctor"}, core.getOutfitNames());
    }

    @Test
    void spawnOriginsAppendAndOnlyStopClearsThem() {
        PopManCore core = new PopManCore();
        core.setSpawnOrigins(new int[] {1, 2, 3, 4});
        core.setSpawnOrigins(new int[] {5, 6, 7, 8});

        assertArrayEquals(
                new int[] {1, 2, 3, 4, 5, 6, 7, 8},
                core.getSpawnOrigins(),
                "the native appends, so a reload duplicates rather than replaces");

        core.stop();
        assertEquals(0, core.getSpawnOrigins().length);
    }

    @Test
    void malformedSpawnOriginsAreRejected() {
        PopManCore core = new PopManCore();

        assertThrows(NullPointerException.class, () -> core.setSpawnOrigins(null));
        assertThrows(IllegalArgumentException.class, () -> core.setSpawnOrigins(new int[] {1, 2}));
        assertThrows(NullPointerException.class, () -> core.setOutfitNames(null));
    }

    /** Saved zombies reference outfits by index, so the table order is load-bearing. */
    @Test
    void outfitNamesAreIndexedInOrderAndDuplicatesTakeTheLastIndex() {
        PopManCore core = new PopManCore();
        core.setOutfitNames(new String[] {"police", "doctor", "police"});

        assertEquals(1, core.outfitIndex("doctor"));
        assertEquals(2, core.outfitIndex("police"));
        assertNull(core.outfitIndex("varsity"));

        core.setOutfitNames(new String[] {"varsity"});
        assertNull(core.outfitIndex("doctor"), "the table is replaced, not merged");
    }

    @Test
    void unknownConfigKeysThrowLikeTheNative() {
        PopManConfig config = new PopManConfig();

        assertThrows(IllegalArgumentException.class, () -> config.setFloat("NoSuchKey", 1.0F));
        assertThrows(IllegalArgumentException.class, () -> config.setInt("NoSuchKey", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> config.setFloat("PopulationPeakDay", 1.0F),
                "PopulationPeakDay is an int key");
        assertThrows(
                IllegalArgumentException.class,
                () -> config.setInt("PopulationMultiplier", 1),
                "PopulationMultiplier is a float key");
    }

    @Test
    void configReloadAppliesEverySandboxKey() {
        PopManConfig config = new PopManConfig();
        config.clearDirty();

        config.setFloat("PopulationMultiplier", 2.0F);
        config.setFloat("PopulationStartMultiplier", 0.5F);
        config.setFloat("PopulationPeakMultiplier", 3.0F);
        config.setInt("PopulationPeakDay", 14);
        config.setFloat("RespawnHours", 72.0F);
        config.setFloat("RespawnUnseenHours", 24.0F);
        config.setFloat("RespawnMultiplier", 0.25F);
        config.setFloat("RedistributeHours", 12.0F);
        config.setInt("FollowSoundDistance", 200);
        config.setFloat("MinZombiesPerChunk", 1.0F);
        config.setFloat("MaxZombiesPerChunk", 100.0F);
        config.setFloat("UniformZombiesPerChunk", 0.2F);

        assertTrue(config.isDirty());
        assertEquals(2.0F, config.populationMultiplier);
        assertEquals(14, config.populationPeakDay);
        assertEquals(200, config.followSoundDistance);
        assertEquals(100.0F, config.maxZombiesPerChunk);
    }

    @Test
    void worldSoundsTravelThroughTheFrameAndAreDedupedByTheWorker() {
        PopManCore core = new PopManCore();
        long[] nanos = {60_000_000_000L};
        core.setClockNanos(() -> nanos[0]);

        core.worldSound(10, 20, 60, 40);
        assertTrue(core.handoff().input().sounds.isEmpty(), "inert before init");

        core.init(false, true, 0, 0, 300, 300);
        core.updateMain(1.0F, 96.0);
        core.worldSound(10, 20, 60, 40);
        core.worldSound(10, 20, 60, 40);
        assertEquals(2, core.handoff().input().sounds.size(), "the sender does not deduplicate");

        core.hasDataForThread();
        core.handoff().drainInput();
        core.absorbInput(core.handoff().workerInput());

        assertEquals(
                1, core.worldSounds().sounds().size(), "a resend refreshes, it does not stack");
        assertEquals(96, core.worldSounds().sounds().get(0).getWorldAgeHours());
        assertEquals(1, core.collectSoundRecruiters().size());

        nanos[0] += 2_000_000_000L;
        assertTrue(core.collectSoundRecruiters().isEmpty());
        assertTrue(core.worldSounds().sounds().isEmpty(), "expired once Java stopped resending");
    }

    @Test
    void mainThreadSettersAccumulateIntoOneFrame() {
        PopManCore core = newRunningCore();

        core.addZombie(1.0F, 2.0F, 0.0F, (byte) 3, 7, 0, 11, 12);
        core.aggroTarget(5, 6, 7);
        core.spawnHorde(0, 0, 8, 8, 4.0F, 4.0F, 10);
        core.loadChunk(30, 40, true);
        core.loadedAreas(1, new int[] {1, 2, 3, 4, 99, 99, 99, 99}, false);
        core.requestRadarData();
        core.configFloat("PopulationMultiplier", 2.0F);
        core.configInt("FollowSoundDistance", 250);

        PopManInputFrame frame = core.handoff().input();
        assertEquals(1, frame.addZombies.size());
        assertEquals(1, frame.aggroTargets.size());
        assertEquals(1, frame.hordes.size());
        assertEquals(1, frame.chunkLoads.size());
        assertArrayEquals(new int[] {1, 2, 3, 4}, frame.loadedAreas, "count is quads, not ints");
        assertTrue(frame.radarRequested);
        assertTrue(frame.configDirty);

        assertTrue(core.hasDataForThread(), "a non-empty frame publishes");
        assertTrue(core.handoff().input().isEmpty());
        assertFalse(core.hasDataForThread(), "a second publish with nothing pending is a no-op");
    }

    @Test
    void unknownConfigKeysAreRejectedAtTheCallSiteNotOnTheWorker() {
        PopManCore core = newRunningCore();

        assertThrows(IllegalArgumentException.class, () -> core.configFloat("NoSuchKey", 1.0F));
        assertThrows(IllegalArgumentException.class, () -> core.configInt("NoSuchKey", 1));
        assertTrue(core.handoff().input().isEmpty(), "a rejected key queues nothing");
    }

    @Test
    void theWorkerAppliesConfigItReceives() {
        PopManCore core = newRunningCore();
        core.configFloat("MaxZombiesPerChunk", 12.5F);
        core.configInt("PopulationPeakDay", 14);
        core.hasDataForThread();

        core.handoff().drainInput();
        core.absorbInput(core.handoff().workerInput());

        assertEquals(12.5F, core.config().maxZombiesPerChunk);
        assertEquals(14, core.config().populationPeakDay);
    }

    @Test
    void spawnResultsAreHandedBackInBufferSizedBatches() {
        PopManCore core = newRunningCore();
        int total = PopManZombie.MAX_ADD_RECORDS + 3;
        for (int i = 0; i < total; i++) {
            PopManZombie zombie = new PopManZombie();
            zombie.x = i;
            core.handoff().output().spawns.add(zombie);
        }
        core.handoff().publishResults();
        core.updateMain(1.0F, 24.0);

        assertEquals(total, core.getAddZombieCount());

        ByteBuffer buf = ByteBuffer.allocate(PopManZombie.BUFFER_BYTES);
        int first = core.getAddZombieData(0, buf);
        assertEquals(PopManZombie.MAX_ADD_RECORDS, first, "one buffer's worth at a time");
        assertEquals(0, buf.position(), "vanilla reads relatively from zero, so leave it alone");

        PopManZombie decoded = new PopManZombie();
        decoded.readAddRecord(buf, 0);
        assertEquals(0.0F, decoded.x);

        int second = core.getAddZombieData(first, buf);
        assertEquals(3, second);
        decoded.readAddRecord(buf, 0);
        assertEquals((float) first, decoded.x, "the second batch resumes at the offset");
    }

    @Test
    void radarResultsOnlyExistForTheTickTheyArriveIn() {
        PopManCore core = newRunningCore();
        core.handoff().output().radarXY = new float[] {1.0F, 2.0F, 3.0F, 4.0F};
        core.handoff().output().radarSet = true;
        core.handoff().publishResults();
        core.updateMain(1.0F, 24.0);

        assertTrue(core.hasRadarData());
        float[] xy = new float[8];
        assertEquals(2, core.getRadarZombieData(xy), "the return is zombies, not floats");
        assertEquals(3.0F, xy[2]);

        core.updateMain(1.0F, 24.0);
        assertFalse(core.hasRadarData());
        assertEquals(0, core.getRadarZombieData(xy));
    }

    @Test
    void aTimeChangeAloneIsWorthWakingTheWorkerFor() {
        PopManCore core = newRunningCore();
        core.updateMain(1.0F, 24.0);

        assertTrue(core.hasDataForThread());
        core.handoff().drainInput();
        assertEquals(24.0, core.handoff().workerInput().worldAgeHours);

        core.updateMain(1.0F, 24.0);
        assertFalse(core.hasDataForThread(), "an unchanged tick queues nothing");
    }

    /**
     * The game's worker loop parks on {@code shouldWait()} right after the main thread wakes it. A
     * published frame is what it was woken for, so it must count as pending work even though the
     * main-side frame is fresh and empty again — otherwise the wake-up is lost for good.
     */
    @Test
    void aPublishedFrameKeepsTheWorkerAwakeUntilItIsDrained() {
        PopManCore core = newRunningCore();
        core.updateMain(1.0F, 2.0);

        assertTrue(core.hasDataForThread(), "the time change publishes a frame");
        assertFalse(core.shouldWait(), "a published, undrained frame is pending work");

        core.updateThread();
        assertEquals(2.0, core.worldAgeHours());
        assertTrue(core.shouldWait(), "drained, with nothing due, the worker may park");
    }

    /**
     * Repopulation peeks at a cell's eight neighbours to read their totals. Treating those peeked
     * cells as active would peek at their neighbours next tick, and flood-fill the whole map within
     * seconds — which is exactly what happened live.
     */
    @Test
    void neighbourPeeksDoNotFanOutAcrossTheMap() {
        PopManCore core = newRunningCore();
        core.configFloat("RespawnHours", 72.0F);
        core.loadedAreas(1, new int[] {150 * 32 + 4, 150 * 32 + 4, 8, 8}, false);
        for (int tick = 0; tick < 6; tick++) {
            core.updateMain(1.0F, 100.0 + tick);
            core.hasDataForThread();
            core.updateThread();
        }

        assertEquals(1, core.cells().active().size(), "only the cell under the area is active");
        assertEquals(9, core.cells().size(), "the active cell plus its eight peeked neighbours");
        assertTrue(core.shouldWait(), "peeked neighbours owe no respawn work");
    }

    @Test
    void loadedAreasOutsideTheWorldLoadNothing() {
        PopManCore core = newRunningCore();
        core.loadedAreas(1, new int[] {-64, -64, 8, 8}, false);
        core.updateMain(1.0F, 1.0);
        core.hasDataForThread();
        core.updateThread();

        assertEquals(0, core.cells().size());
    }

    private static PopManCore newRunningCore() {
        PopManCore core = new PopManCore();
        core.init(false, true, 0, 0, 300, 300);
        return core;
    }

    @Test
    void realZombieBatchesStageUntilTheNextBegin() {
        PopManCore core = newRunningCore();
        ByteBuffer buf = ByteBuffer.allocate(PopManZombie.BUFFER_BYTES);

        core.beginSaveRealZombies(2);
        PopManZombie zombie = new PopManZombie();
        zombie.x = 1200.5F;
        zombie.descriptorID = 0x00010007;
        zombie.stateFlags = 8;
        zombie.writeSaveRecord(buf, 0);
        zombie.x = 1300.5F;
        zombie.writeSaveRecord(buf, 1);
        core.saveRealZombies(2, buf);
        core.saveRealZombies(1, buf);

        assertEquals(3, core.stagedRealZombies().size(), "batches append");
        assertEquals(1200.5F, core.stagedRealZombies().get(2).x);
        assertEquals(0x00010007, core.stagedRealZombies().get(0).descriptorID);
        assertEquals(
                PopManZombie.INVALID_PATH_XY,
                core.stagedRealZombies().get(0).pathTargetX,
                "a real zombie carries no virtual path target");

        core.beginSaveRealZombies(0);
        assertTrue(core.stagedRealZombies().isEmpty());
    }

    /** The staging record honours the buffer's own order; the on-disk record is always BE. */
    @Test
    void realZombieStagingFollowsTheBuffersByteOrder() {
        PopManCore core = newRunningCore();
        ByteBuffer little = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        PopManZombie zombie = new PopManZombie();
        zombie.x = 42.0F;
        zombie.stateFlags = 0x11223344;
        zombie.writeSaveRecord(little, 0);

        core.beginSaveRealZombies(1);
        core.saveRealZombies(1, little);

        assertEquals(42.0F, core.stagedRealZombies().get(0).x);
        assertEquals(0x11223344, core.stagedRealZombies().get(0).stateFlags);
        assertEquals(
                0x44332211,
                ByteBuffer.wrap(little.array()).getInt(17),
                "read back as big-endian the same bytes mean something else");
    }
}
