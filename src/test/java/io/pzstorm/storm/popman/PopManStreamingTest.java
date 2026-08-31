package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

class PopManStreamingTest implements UnitTest {

    private final ScriptedWorld world = new ScriptedWorld();
    private final PopManConfig config = new PopManConfig();
    private final PopManCellMap cells = new PopManCellMap(config, world, cell -> false);
    private final PopManResultFrame out = new PopManResultFrame();

    PopManStreamingTest() {
        world.setWorldBounds(-4, -4, 8, 8);
        world.densityByte = PopManPopulation.NO_DENSITY_DATA;
    }

    private PopManChunk populate(PopManCell cell, int chunkX, int chunkY, int count) {
        PopManChunk chunk = cell.chunkAt(chunkX, chunkY);
        for (int i = 0; i < count; i++) {
            chunk.zombies.add(
                    PopManZombie.spawnedAt(chunk.minSquareX() + i, chunk.minSquareY(), () -> 0));
        }
        cell.virtualCount += (short) count;
        return chunk;
    }

    @Test
    void streamingInHandsTheChunksZombiesToTheGame() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        PopManChunk chunk = populate(cell, 3, 4, 6);

        PopManStreaming.applyChunkLoad(cells, 3, 4, true, 500L, out);

        assertEquals(6, out.spawns.size());
        assertTrue(chunk.zombies.isEmpty(), "they are no longer the population's to move");
        assertEquals(0, cell.virtualCount);
        assertEquals(6, cell.realCount);
        assertTrue(cell.isChunkStreamedIn(3, 4));
        assertTrue(cell.dirty);
    }

    @Test
    void anEmptyChunkStillRecordsThatItIsStreamedIn() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        cell.dirty = false;

        PopManStreaming.applyChunkLoad(cells, 3, 4, true, 500L, out);

        assertTrue(cell.isChunkStreamedIn(3, 4));
        assertTrue(out.spawns.isEmpty());
        assertFalse(cell.dirty, "nothing moved, so nothing needs saving");
    }

    @Test
    void streamingOutOnlyClearsTheFlag() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        PopManChunk chunk = populate(cell, 3, 4, 2);
        PopManStreaming.applyChunkLoad(cells, 3, 4, true, 500L, out);
        out.reset();

        PopManStreaming.applyChunkLoad(cells, 3, 4, false, 600L, out);

        assertFalse(cell.isChunkStreamedIn(3, 4));
        assertTrue(out.spawns.isEmpty(), "the game hands survivors back by another route");
        assertTrue(chunk.zombies.isEmpty());
    }

    @Test
    void aChunkInACellNobodyHasVisitedIsIgnored() {
        PopManStreaming.applyChunkLoad(cells, 3, 4, true, 500L, out);

        assertEquals(0, cells.size(), "streaming must not conjure a populated cell");
        assertTrue(out.spawns.isEmpty());
    }

    @Test
    void aChunkOutsideTheWorldIsIgnored() {
        cells.load(0, 0, 0, 0);

        PopManStreaming.applyChunkLoad(cells, 4 * 32, 0, true, 500L, out);

        assertTrue(out.spawns.isEmpty());
    }

    @Test
    void loadingTwiceDoesNotRealiseTheSameZombiesAgain() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        populate(cell, 3, 4, 6);

        PopManStreaming.applyChunkLoad(cells, 3, 4, true, 500L, out);
        PopManStreaming.applyChunkLoad(cells, 3, 4, true, 600L, out);

        assertEquals(6, out.spawns.size());
        assertEquals(6, cell.realCount, "the tallies must not drift on a repeated load");
        assertEquals(0, cell.virtualCount);
    }

    @Test
    void theOwningCellIsTouchedSoItIsNotEvictedUnderAPlayer() {
        PopManCell cell = cells.load(0, 0, 0, 900L);

        PopManStreaming.applyChunkLoad(cells, 3, 4, true, 5000L, out);

        assertEquals(5000L, cell.lastTouchedMs);
        assertSame(cell, cells.resident(0, 0));
    }

    @Test
    void negativeChunksResolveToTheirOwnCell() {
        PopManCell cell = cells.load(-1, -1, 0, 0);
        populate(cell, -3, -3, 2);

        PopManStreaming.applyChunkLoad(cells, -3, -3, true, 500L, out);

        assertEquals(2, out.spawns.size());
        assertTrue(cell.isChunkStreamedIn(-3, -3));
    }
}
