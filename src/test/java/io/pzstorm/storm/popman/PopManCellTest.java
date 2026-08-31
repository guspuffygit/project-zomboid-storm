package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

class PopManCellTest implements UnitTest {

    @Test
    void aCellOwnsAllItsChunksFromTheStartAndTheyKnowTheirWorldCoordinates() {
        PopManCell cell = new PopManCell(-1, 2);

        assertEquals(-256, cell.minSquareX());
        assertEquals(512, cell.minSquareY());

        PopManChunk corner = cell.chunkAt(-32, 64);
        assertEquals(-32, corner.chunkX);
        assertEquals(64, corner.chunkY);
        assertEquals(-256, corner.minSquareX());

        assertSame(corner, cell.chunkAtSquare(-256, 512));
        assertSame(corner, cell.chunkAtSquare(-249, 519));
    }

    @Test
    void currentPopulationCountsRealVirtualAndPassingThrough() {
        PopManCell cell = new PopManCell(0, 0);
        cell.realCount = 7;
        cell.virtualCount = 5;

        PopManGroup pair = new PopManGroup(new PopManZombie());
        pair.members.add(new PopManZombie());
        cell.groups.add(pair);

        assertEquals(14, cell.currentPopulation());
    }

    @Test
    void aGroupWithNoMemberListStillCountsAsOne() {
        PopManGroup group = new PopManGroup(new PopManZombie());
        group.members.clear();

        assertEquals(1, group.population());
    }

    @Test
    void aggregatesAreRebuiltFromTheChunks() {
        PopManCell cell = new PopManCell(0, 0);
        cell.chunkAt(0, 0).basePop = 3;
        cell.chunkAt(0, 0).zombies.add(new PopManZombie());
        cell.chunkAt(5, 5).basePop = 4;
        cell.chunkAt(5, 5).zombies.add(new PopManZombie());
        cell.chunkAt(5, 5).zombies.add(new PopManZombie());

        cell.recomputeAggregates();

        assertEquals(7, cell.basePopSum);
        assertEquals(3, cell.virtualCount);
    }

    @Test
    void theStreamedInBitmapIsPerChunkAndSurvivesItsNeighbours() {
        PopManCell cell = new PopManCell(-1, -1);

        assertFalse(cell.isChunkStreamedIn(-32, -32));
        cell.setChunkStreamedIn(-32, -32, true);
        cell.setChunkStreamedIn(-1, -1, true);

        assertTrue(cell.isChunkStreamedIn(-32, -32));
        assertTrue(cell.isChunkStreamedIn(-1, -1));
        assertFalse(cell.isChunkStreamedIn(-31, -32));

        cell.setChunkStreamedIn(-32, -32, false);
        assertFalse(cell.isChunkStreamedIn(-32, -32));
        assertTrue(cell.isChunkStreamedIn(-1, -1), "clearing one bit must not clear its row");
    }

    @Test
    void clocksInTheFutureAreClampedBackToNow() {
        PopManChunk chunk = new PopManChunk(0, 0);
        chunk.lastSeenTime = 500.0F;
        chunk.lastRepopTime = 500.0F;

        chunk.clampClocks(100.0F);

        assertEquals(100.0F, chunk.lastSeenTime);
        assertEquals(100.0F, chunk.lastRepopTime);
    }
}
