package io.pzstorm.storm.popman;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One batch of main-thread input for the population worker — native type A, {@code 0xf8} bytes.
 * Setters accumulate into the frame currently held by {@link PopManHandoff}; publishing swaps in a
 * fresh one.
 *
 * <p>The native recycling fields ({@code A+0xe8}) are deliberately absent: they existed only to
 * refill the worker's object pool, which GC does here.
 */
public final class PopManInputFrame {

    /** {@code {x, y, w, h}} quads in chunk coordinates. */
    public int[] loadedAreas;

    public int[] loadedServerCells;

    public double worldAgeHours;
    public float timeMultiplier;
    public boolean timeChanged;

    public final Map<String, Float> floatConfig = new LinkedHashMap<>();
    public final Map<String, Integer> intConfig = new LinkedHashMap<>();
    public boolean configDirty;

    /** Three shorts per cell, as handed over by {@code n_realZombieCount}. */
    public short[] realZombieCounts;

    public final List<AggroTarget> aggroTargets = new ArrayList<>();
    public final List<ChunkLoad> chunkLoads = new ArrayList<>();
    public final List<PopManZombie> addZombies = new ArrayList<>();
    public final List<HordeRequest> hordes = new ArrayList<>();
    public final List<PopManWorldSound> sounds = new ArrayList<>();

    public boolean radarRequested;
    public boolean mpDebugRequested;

    public final List<DebugCommand> debugCommands = new ArrayList<>();

    /** {@code n_debugCommand(type, cellX, cellY)} — absolute cell coordinates. */
    public record DebugCommand(int type, int cellX, int cellY) {
        public static final int SPAWN_TIME_TO_ZERO = 3;
        public static final int CLEAR_ZOMBIES = 4;
        public static final int SPAWN_NOW = 5;
    }

    public record AggroTarget(int id, int x, int y) {}

    public record ChunkLoad(int worldX, int worldY, boolean loaded) {}

    public record HordeRequest(
            int spawnX,
            int spawnY,
            int spawnW,
            int spawnH,
            float targetX,
            float targetY,
            int count) {}

    public boolean isEmpty() {
        return loadedAreas == null
                && loadedServerCells == null
                && !timeChanged
                && !configDirty
                && realZombieCounts == null
                && aggroTargets.isEmpty()
                && chunkLoads.isEmpty()
                && addZombies.isEmpty()
                && hordes.isEmpty()
                && sounds.isEmpty()
                && !radarRequested
                && !mpDebugRequested
                && debugCommands.isEmpty();
    }

    public void reset() {
        loadedAreas = null;
        loadedServerCells = null;
        timeChanged = false;
        floatConfig.clear();
        intConfig.clear();
        configDirty = false;
        realZombieCounts = null;
        aggroTargets.clear();
        chunkLoads.clear();
        addZombies.clear();
        hordes.clear();
        sounds.clear();
        radarRequested = false;
        mpDebugRequested = false;
        debugCommands.clear();
    }

    /**
     * Folds this frame into an accumulator. Lists append, flags OR, scalars overwrite — taking the
     * newest frame wholesale instead would silently drop everything the older frames carried.
     */
    public void mergeInto(PopManInputFrame accumulator) {
        if (loadedAreas != null) {
            accumulator.loadedAreas = loadedAreas;
        }
        if (loadedServerCells != null) {
            accumulator.loadedServerCells = loadedServerCells;
        }
        if (timeChanged) {
            accumulator.timeChanged = true;
            accumulator.worldAgeHours = worldAgeHours;
            accumulator.timeMultiplier = timeMultiplier;
        }
        if (configDirty) {
            accumulator.floatConfig.putAll(floatConfig);
            accumulator.intConfig.putAll(intConfig);
            accumulator.configDirty = true;
        }
        if (realZombieCounts != null) {
            accumulator.realZombieCounts = realZombieCounts;
        }
        accumulator.aggroTargets.addAll(aggroTargets);
        accumulator.chunkLoads.addAll(chunkLoads);
        accumulator.addZombies.addAll(addZombies);
        accumulator.hordes.addAll(hordes);
        accumulator.sounds.addAll(sounds);
        accumulator.radarRequested |= radarRequested;
        accumulator.mpDebugRequested |= mpDebugRequested;
        accumulator.debugCommands.addAll(debugCommands);
    }
}
