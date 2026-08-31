package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PopManGroupingTest implements UnitTest {

    private final ScriptedWorld world = new ScriptedWorld();
    private final PopManConfig config = new PopManConfig();
    private final PopManCellMap cells = new PopManCellMap(config, world, cell -> false);
    private final List<PopManGroup> groups = new ArrayList<>();
    private final PopManGrouping grouping = new PopManGrouping(config, world, cells, groups);

    PopManGroupingTest() {
        world.setWorldBounds(-4, -4, 8, 8);
        world.densityByte = PopManPopulation.NO_DENSITY_DATA;
        config.redistributeHours = 6.0F;
    }

    private PopManChunk fill(PopManCell cell, int chunkX, int chunkY, int count) {
        PopManChunk chunk = cell.chunkAt(chunkX, chunkY);
        for (int i = 0; i < count; i++) {
            chunk.zombies.add(
                    PopManZombie.spawnedAt(chunk.minSquareX() + i, chunk.minSquareY(), () -> 0));
        }
        cell.virtualCount += (short) count;
        return chunk;
    }

    @Test
    void redistributionIsOffUntilItIsConfigured() {
        config.redistributeHours = 0.0F;
        PopManCell cell = cells.load(0, 0, 0, 0);
        fill(cell, 0, 0, 20);

        grouping.redistributeAll(1000.0);

        assertTrue(groups.isEmpty());
    }

    @Test
    void aCellIsRedistributedOnlyOnceItsIntervalHasElapsed() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        fill(cell, 0, 0, 20);
        cell.lastRedistributeTime = 10.0F;

        grouping.redistributeAll(14.0);
        assertTrue(groups.isEmpty(), "four hours into a six hour interval");

        grouping.redistributeAll(20.0);
        assertTrue(!groups.isEmpty());
        assertEquals(20.0F, cell.lastRedistributeTime);
    }

    @Test
    void aClockAheadOfTheWorldIsPulledBackRatherThanTrusted() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        cell.lastRedistributeTime = 5000.0F;

        grouping.redistributeAll(12.0);

        assertEquals(12.0F, cell.lastRedistributeTime, "a rewound world must not freeze the cell");
    }

    @Test
    void anOvercrowdedChunkShedsDownToTheThreshold() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        PopManChunk chunk = fill(cell, 0, 0, 8);

        grouping.redistributeCell(cell);

        assertEquals(1, groups.size());
        assertEquals(PopManGroup.MAX_REDISTRIBUTE_MEMBERS, groups.get(0).members.size());
        assertEquals(3, chunk.zombies.size(), "the group is lifted out of the chunk");
        assertEquals(3, cell.virtualCount, "and stops counting as resident");
    }

    @Test
    void aChunkAtOrUnderTheThresholdIsLeftAlone() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        PopManChunk chunk = fill(cell, 0, 0, PopManGrouping.CROWDING_THRESHOLD);

        grouping.redistributeCell(cell);

        assertTrue(groups.isEmpty());
        assertEquals(PopManGrouping.CROWDING_THRESHOLD, chunk.zombies.size());
    }

    @Test
    void aGroupKnowsWhichCellItIsPassingThrough() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        fill(cell, 0, 0, 8);

        grouping.redistributeCell(cell);

        assertSame(cell, groups.get(0).cell);
        assertTrue(cell.dirty);
    }

    @Test
    void aShedGroupWalksTowardsTheMiddleOfAnEmptyChunk() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        fill(cell, 0, 0, 8);

        grouping.redistributeCell(cell);

        PopManZombie leader = groups.get(0).leader;
        int half = PopManGeometry.SQUARES_PER_CHUNK / 2;
        assertEquals(half, Math.floorMod(leader.pathTargetX, PopManGeometry.SQUARES_PER_CHUNK));
        assertEquals(half, Math.floorMod(leader.pathTargetY, PopManGeometry.SQUARES_PER_CHUNK));
    }

    @Test
    void zombiesPlayingDeadStayWhereTheyAre() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        PopManChunk chunk = cell.chunkAt(0, 0);
        for (int i = 0; i < 8; i++) {
            PopManZombie zombie = PopManZombie.spawnedAt(i, 0, () -> 0);
            zombie.stateFlags |= 8;
            chunk.zombies.add(zombie);
        }

        grouping.redistributeCell(cell);

        assertTrue(groups.isEmpty());
        assertEquals(8, chunk.zombies.size());
    }

    @Test
    void zombiesOffTheGroundFloorAreNotHerded() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        PopManChunk chunk = cell.chunkAt(0, 0);
        for (int i = 0; i < 8; i++) {
            PopManZombie zombie = PopManZombie.spawnedAt(i, 0, () -> 0);
            zombie.z = 1.0F;
            chunk.zombies.add(zombie);
        }

        grouping.redistributeCell(cell);

        assertTrue(groups.isEmpty());
    }

    @Test
    void aZombieWalledOffFromTheLeaderIsLeftBehind() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        PopManChunk chunk = cell.chunkAt(0, 0);
        for (int i = 0; i < 8; i++) {
            chunk.zombies.add(PopManZombie.spawnedAt(i < 4 ? i : i + 1, 0, () -> 0));
        }
        world.blockColumn(4, -1, 8);

        grouping.redistributeCell(cell);

        assertEquals(1, groups.size());
        for (PopManZombie member : groups.get(0).members) {
            assertTrue(member.x < 4.0F, "nobody from behind the wall joined");
        }
    }

    @Test
    void aSoundGathersAHordeAndAimsItAtTheNoise() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        fill(cell, 14, 14, 3);
        PopManWorldSound sound = new PopManWorldSound(100, 100, 50, 30);

        grouping.recruitForSound(sound, 0.0, 0);

        assertEquals(1, groups.size());
        PopManGroup group = groups.get(0);
        assertEquals(3, group.members.size());
        assertSame(sound, group.followedSound);
        assertEquals(92, group.leader.pathTargetX, "aimed a quarter of the way back towards home");
        assertEquals(92, group.leader.pathTargetY);
        assertEquals(0, group.squaresTravelled);
    }

    @Test
    void zombiesAlreadyOnTopOfTheNoiseAreLeftToTheGame() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        PopManChunk near = fill(cell, 12, 12, 3);
        PopManWorldSound sound = new PopManWorldSound(100, 100, 50, 30);

        grouping.recruitForSound(sound, 0.0, 0);

        assertTrue(groups.isEmpty());
        assertEquals(3, near.zombies.size());
    }

    @Test
    void aSoundHordeIsCappedAndTheRemainderFormsAnother() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        PopManChunk chunk = cell.chunkAt(14, 14);
        for (int i = 0; i < PopManGroup.MAX_SOUND_MEMBERS + 4; i++) {
            chunk.zombies.add(
                    PopManZombie.spawnedAt(
                            chunk.minSquareX() + (i % 8), chunk.minSquareY() + (i / 8), () -> 0));
        }
        cell.virtualCount += (short) (PopManGroup.MAX_SOUND_MEMBERS + 4);

        grouping.recruitForSound(new PopManWorldSound(100, 100, 50, 30), 0.0, 0);

        assertEquals(2, groups.size());
        assertEquals(PopManGroup.MAX_SOUND_MEMBERS, groups.get(0).members.size());
        assertEquals(4, groups.get(1).members.size());
        assertTrue(chunk.zombies.isEmpty(), "everyone left");
    }

    @Test
    void redistributionAndSoundRecruitmentDoNotShareAFill() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        fill(cell, 0, 0, 8);
        fill(cell, 14, 14, 3);

        grouping.redistributeCell(cell);
        grouping.recruitForSound(new PopManWorldSound(100, 100, 50, 30), 0.0, 0);

        assertEquals(2, groups.size());
        assertNull(groups.get(0).followedSound);
        assertEquals(3, groups.get(1).members.size(), "the sound fill was not clobbered");
    }
}
