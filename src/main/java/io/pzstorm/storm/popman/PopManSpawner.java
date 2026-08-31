package io.pzstorm.storm.popman;

import java.util.ArrayList;
import java.util.List;

/**
 * Placing virtual zombies on the map: how a chunk gets its base population, and how a cell that has
 * never been saved gets its first inhabitants.
 */
public final class PopManSpawner {

    /** Tries to find a spawnable square inside one chunk before giving up on it. */
    public static final int SQUARE_TRIES_PER_CHUNK = 100;

    /** Tries to place one top-up zombie anywhere in the cell before giving up on it. */
    public static final int TOP_UP_TRIES = 900;

    private PopManSpawner() {}

    /**
     * Sets and returns the chunk's base population. A blocked chunk gets zero without consuming a
     * dice roll, which keeps the sequence of draws matching the native's.
     */
    public static short computeChunkBasePop(
            PopManChunk chunk, PopManConfig config, PopManMap world) {
        chunk.basePop = 0;
        if (world.areZombiesDisabled() || world.isChunkBlocked(chunk.chunkX, chunk.chunkY)) {
            return 0;
        }
        float density =
                PopManPopulation.chunkDensity(
                        config,
                        world.densityByte(chunk.chunkX, chunk.chunkY),
                        world.isUniformDensityMode());
        chunk.basePop = PopManPopulation.stochasticBasePop(density, () -> world.random(100));
        return chunk.basePop;
    }

    /**
     * Places one zombie on a random spawnable square of the chunk, or gives up. Returns the zombie
     * so the caller can count it; it has already been added to the chunk.
     */
    public static PopManZombie spawnOneInChunk(PopManChunk chunk, PopManMap world) {
        for (int attempt = 0; attempt < SQUARE_TRIES_PER_CHUNK; attempt++) {
            int squareX = chunk.minSquareX() + world.random(PopManGeometry.SQUARES_PER_CHUNK);
            int squareY = chunk.minSquareY() + world.random(PopManGeometry.SQUARES_PER_CHUNK);
            if (!world.isValidSpawnSquare(squareX, squareY, 0)) {
                continue;
            }
            PopManZombie zombie =
                    PopManZombie.spawnedAt(
                            squareX, squareY, () -> world.random(PopManZombie.DIRECTION_COUNT));
            chunk.zombies.add(zombie);
            return zombie;
        }
        return null;
    }

    /**
     * Gives a cell that had no save file its whole starting population at once, distributed across
     * chunks in proportion to their base populations.
     *
     * <p>The caller is responsible for {@link PopManCell#recomputeAggregates()} afterwards — the
     * native populates and aggregates as two separate steps, and the neighbour lazy-load path
     * depends on that order.
     */
    public static void populateVirginCell(
            PopManCell cell, PopManConfig config, PopManMap world, double worldAgeHours) {

        float age = (float) worldAgeHours;
        int totalBasePop = 0;
        List<PopManChunk> populated = new ArrayList<>();
        for (PopManChunk chunk : cell.chunks) {
            if (computeChunkBasePop(chunk, config, world) > 0) {
                totalBasePop += chunk.basePop;
                populated.add(chunk);
            }
            chunk.lastRepopTime = age;
        }

        if (!populated.isEmpty()) {
            int desired = PopManPopulation.desiredCellPopulation(config, (short) totalBasePop, age);
            int spawned = 0;
            for (PopManChunk chunk : populated) {
                int want = (int) (((float) chunk.basePop / totalBasePop) * desired);
                if (totalBasePop <= desired && want < 1) {
                    want = 1;
                }
                for (int i = 0; i < want; i++) {
                    if (spawnOneInChunk(chunk, world) != null) {
                        spawned++;
                    }
                }
            }
            int shortfall = desired - spawned;
            for (int i = 0; i < shortfall; i++) {
                for (int attempt = 0; attempt < TOP_UP_TRIES; attempt++) {
                    PopManChunk chunk = populated.get(world.random(populated.size()));
                    if (spawnOneInChunk(chunk, world) != null) {
                        break;
                    }
                }
            }
        }

        cell.lastRepopTime = age;
        cell.lastRedistributeTime = age;
        cell.dirty = true;
    }
}
