package io.pzstorm.storm.popman;

import java.util.ArrayList;
import java.util.List;

/**
 * One batch of worker output — native type B, {@code 0x90} bytes. The main thread resets its
 * accumulator at the top of every {@code updateMain} and merges every frame the worker published
 * since the last tick, so results are valid only until the next tick.
 */
public final class PopManResultFrame {

    /** Virtual zombies the worker wants realised, drained by {@code n_getAddZombieData}. */
    public final List<PopManZombie> spawns = new ArrayList<>();

    /** Interleaved x,y pairs. */
    public float[] radarXY;

    public boolean radarSet;

    /** One per resident cell, in the layout {@code MPDebugInfo.serverUpdate} reads back. */
    public final List<MpDebugCell> mpDebugCells = new ArrayList<>();

    public boolean mpDebugSet;

    public int[] loadedAreas;
    public int[] loadedServerCells;

    /** Server-only: one entry per repopulation batch, for the multiplayer debug overlay. */
    public final List<RepopEvent> repopEvents = new ArrayList<>();

    public record MpDebugCell(
            short cellX,
            short cellY,
            short currentPopulation,
            short desiredPopulation,
            float lastRepopTime) {}

    public record RepopEvent(int chunkX, int chunkY, float worldAgeHours) {}

    public boolean isEmpty() {
        return spawns.isEmpty()
                && !radarSet
                && mpDebugCells.isEmpty()
                && !mpDebugSet
                && repopEvents.isEmpty();
    }

    public void reset() {
        spawns.clear();
        radarXY = null;
        radarSet = false;
        mpDebugCells.clear();
        mpDebugSet = false;
        repopEvents.clear();
        loadedAreas = null;
        loadedServerCells = null;
    }

    public void mergeInto(PopManResultFrame accumulator) {
        accumulator.spawns.addAll(spawns);
        if (radarSet) {
            accumulator.radarXY = radarXY;
            accumulator.radarSet = true;
        }
        accumulator.repopEvents.addAll(repopEvents);
        if (mpDebugSet) {
            accumulator.mpDebugCells.clear();
            accumulator.mpDebugCells.addAll(mpDebugCells);
            accumulator.mpDebugSet = true;
            accumulator.loadedAreas = loadedAreas;
            accumulator.loadedServerCells = loadedServerCells;
        }
    }
}
