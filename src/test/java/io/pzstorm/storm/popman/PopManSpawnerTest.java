package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

class PopManSpawnerTest implements UnitTest {

    private static final double PEAK_DAY_AGE = 28 * 24.0;

    /** At default Min 0 / Max 255 / Multiplier 1 the density byte is already zombies per chunk. */
    @Test
    void aWholeDensityNeedsNoDiceRoll() {
        ScriptedWorld world = new ScriptedWorld();
        world.densityByte = 3;
        PopManChunk chunk = new PopManChunk(0, 0);

        assertEquals(3, PopManSpawner.computeChunkBasePop(chunk, new PopManConfig(), world));
        assertEquals(3, chunk.basePop);
        assertEquals(0, world.rollsTaken);
    }

    @Test
    void aBlockedChunkIsSkippedBeforeAnyDiceAreRolled() {
        ScriptedWorld world = new ScriptedWorld();
        world.densityByte = 3;
        world.blocked = true;
        PopManChunk chunk = new PopManChunk(0, 0);

        assertEquals(0, PopManSpawner.computeChunkBasePop(chunk, new PopManConfig(), world));
        assertEquals(0, world.rollsTaken, "a skipped chunk must not shift the draw sequence");
    }

    @Test
    void disabledZombiesLeaveEveryChunkEmpty() {
        ScriptedWorld world = new ScriptedWorld();
        world.densityByte = 200;
        world.zombiesDisabled = true;
        PopManChunk chunk = new PopManChunk(0, 0);

        assertEquals(0, PopManSpawner.computeChunkBasePop(chunk, new PopManConfig(), world));
    }

    @Test
    void aSpawnedZombieSitsAtTheCentreOfItsSquareUndressed() {
        ScriptedWorld world = new ScriptedWorld().roll(3, 5, 6);
        PopManChunk chunk = new PopManChunk(-2, 4);

        PopManZombie zombie = PopManSpawner.spawnOneInChunk(chunk, world);

        assertNotNull(zombie);
        assertEquals(-16 + 3 + 0.5F, zombie.x);
        assertEquals(32 + 5 + 0.5F, zombie.y);
        assertEquals(0.0F, zombie.z);
        assertEquals(6, zombie.dir);
        assertEquals(0, zombie.descriptorID, "the native hard-codes the descriptor");
        assertEquals(PopManZombie.SPAWN_STATE_FLAGS, zombie.stateFlags);
        assertEquals(false, zombie.hasPathTarget());
        assertEquals(1, chunk.zombies.size());
    }

    @Test
    void anUnspawnableChunkIsGivenUpOnAfterAHundredTries() {
        ScriptedWorld world = new ScriptedWorld();
        world.everySquareSpawnable = false;
        PopManChunk chunk = new PopManChunk(0, 0);

        assertNull(PopManSpawner.spawnOneInChunk(chunk, world));
        assertEquals(
                2 * PopManSpawner.SQUARE_TRIES_PER_CHUNK,
                world.rollsTaken,
                "two coordinate draws per try, and no direction draw for a zombie never placed");
        assertTrue(chunk.zombies.isEmpty());
    }

    @Test
    void aVirginCellFillsEveryChunkInProportionToItsDensity() {
        ScriptedWorld world = new ScriptedWorld();
        world.densityByte = 3;
        PopManCell cell = new PopManCell(0, 0);

        PopManSpawner.populateVirginCell(cell, new PopManConfig(), world, 0.0);
        cell.recomputeAggregates();

        assertEquals(3 * PopManGeometry.CHUNKS_PER_CELL_TOTAL, cell.basePopSum & 0xFFFF);
        assertEquals(3, cell.chunkAt(17, 29).zombies.size());
        assertTrue(cell.dirty);
        assertEquals(0.0F, cell.lastRepopTime);
        assertEquals(0.0F, cell.lastRedistributeTime);
        assertEquals(0.0F, cell.chunkAt(17, 29).lastRepopTime);
    }

    /**
     * A per-chunk share that rounds down leaves the cell under its target; the top-up loop is the
     * only thing that closes that gap, and without it a fresh cell is a third short.
     */
    @Test
    void theTopUpLoopClosesTheRoundingGap() {
        ScriptedWorld world = new ScriptedWorld();
        world.densityByte = 1;
        PopManCell cell = new PopManCell(0, 0);

        PopManSpawner.populateVirginCell(cell, new PopManConfig(), world, PEAK_DAY_AGE);
        cell.recomputeAggregates();

        int total = 0;
        for (PopManChunk chunk : cell.chunks) {
            total += chunk.zombies.size();
        }
        int base = PopManGeometry.CHUNKS_PER_CELL_TOTAL;
        assertEquals(base, cell.basePopSum, "one zombie of base population per chunk");
        assertEquals(
                PopManPopulation.desiredCellPopulation(new PopManConfig(), base, PEAK_DAY_AGE),
                total);
        assertEquals(base + base / 2, total);
    }

    @Test
    void aCellWithNoDensityAnywhereStillStampsItsClocks() {
        ScriptedWorld world = new ScriptedWorld();
        world.densityByte = PopManPopulation.NO_DENSITY_DATA;
        PopManCell cell = new PopManCell(0, 0);

        PopManSpawner.populateVirginCell(cell, new PopManConfig(), world, 40.0);
        cell.recomputeAggregates();

        assertEquals(0, cell.virtualCount);
        assertEquals(0, cell.basePopSum);
        assertEquals(40.0F, cell.lastRepopTime);
        assertTrue(cell.dirty);
    }
}
