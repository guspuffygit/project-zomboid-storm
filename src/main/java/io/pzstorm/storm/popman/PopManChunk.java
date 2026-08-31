package io.pzstorm.storm.popman;

import java.util.ArrayList;
import java.util.List;

/**
 * One 8x8 chunk of the live population — native {@code Chunk}, 0x20 bytes. A cell owns 1024 of
 * these whether or not they hold anything.
 *
 * <p>The two clocks are in-game hours measured against the same world age {@code n_updateMain}
 * pushes, and both are persisted, so they survive a restart.
 */
public final class PopManChunk {

    public final int chunkX;
    public final int chunkY;

    /** The chunk's stochastically-rounded density target — see {@link PopManPopulation}. */
    public short basePop;

    public final List<PopManZombie> zombies = new ArrayList<>();

    /** When a player last had this chunk in view; gates {@code RespawnUnseenHours}. */
    public float lastSeenTime;

    /** When this chunk last took a repopulation batch; gates {@code RespawnHours}. */
    public float lastRepopTime;

    public PopManChunk(int chunkX, int chunkY) {
        this.chunkX = chunkX;
        this.chunkY = chunkY;
    }

    public int minSquareX() {
        return chunkX * PopManGeometry.SQUARES_PER_CHUNK;
    }

    public int minSquareY() {
        return chunkY * PopManGeometry.SQUARES_PER_CHUNK;
    }

    /**
     * Clamps a clock that sits in the future back to now. The native does this on every pass so a
     * config-driven age reset or a clock skew cannot wedge a chunk out of repopulation forever.
     */
    public void clampClocks(float worldAgeHours) {
        lastSeenTime = Math.min(lastSeenTime, worldAgeHours);
        lastRepopTime = Math.min(lastRepopTime, worldAgeHours);
    }
}
