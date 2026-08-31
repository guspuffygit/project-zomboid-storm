package io.pzstorm.storm.popman;

import java.util.Set;

/**
 * Sandbox-driven tuning values for the population simulation, mirroring the {@code std::map<string,
 * float>} / {@code std::map<string, int>} pair the native popman kept at {@code obj+0x40} and
 * {@code obj+0x50}. Keys and their float/int split were recovered from {@code
 * ZombiePopulationManager.onConfigReloaded()}; an unrecognised key threw {@link
 * IllegalArgumentException} natively, so it does here too.
 */
public final class PopManConfig {

    static final Set<String> FLOAT_KEYS =
            Set.of(
                    "PopulationMultiplier",
                    "PopulationStartMultiplier",
                    "PopulationPeakMultiplier",
                    "RespawnHours",
                    "RespawnUnseenHours",
                    "RespawnMultiplier",
                    "RedistributeHours",
                    "MinZombiesPerChunk",
                    "MaxZombiesPerChunk",
                    "UniformZombiesPerChunk");

    static final Set<String> INT_KEYS = Set.of("PopulationPeakDay", "FollowSoundDistance");

    /**
     * The native JNI setter validated the key before it ever reached the worker, so a typo in a
     * sandbox file surfaces at the call site rather than being dropped silently on another thread.
     */
    public static void requireFloatKey(String key) {
        if (!FLOAT_KEYS.contains(key)) {
            throw new IllegalArgumentException("unknown float config key: " + key);
        }
    }

    public static void requireIntKey(String key) {
        if (!INT_KEYS.contains(key)) {
            throw new IllegalArgumentException("unknown int config key: " + key);
        }
    }

    public float populationMultiplier = 1.0F;
    public float populationStartMultiplier = 1.0F;
    public float populationPeakMultiplier = 1.5F;
    public int populationPeakDay = 28;
    public float respawnHours;
    public float respawnUnseenHours = 16.0F;
    public float respawnMultiplier = 0.1F;
    public float redistributeHours;
    public int followSoundDistance = 100;
    public float minZombiesPerChunk;
    public float maxZombiesPerChunk = 255.0F;
    public float uniformZombiesPerChunk = 0.2F;

    /** Set whenever a value changes so derived per-chunk targets get recomputed. */
    private boolean dirty = true;

    public void setFloat(String key, float value) {
        switch (key) {
            case "PopulationMultiplier" -> populationMultiplier = value;
            case "PopulationStartMultiplier" -> populationStartMultiplier = value;
            case "PopulationPeakMultiplier" -> populationPeakMultiplier = value;
            case "RespawnHours" -> respawnHours = value;
            case "RespawnUnseenHours" -> respawnUnseenHours = value;
            case "RespawnMultiplier" -> respawnMultiplier = value;
            case "RedistributeHours" -> redistributeHours = value;
            case "MinZombiesPerChunk" -> minZombiesPerChunk = value;
            case "MaxZombiesPerChunk" -> maxZombiesPerChunk = value;
            case "UniformZombiesPerChunk" -> uniformZombiesPerChunk = value;
            default -> throw new IllegalArgumentException("unknown float config key: " + key);
        }
        dirty = true;
    }

    public void setInt(String key, int value) {
        switch (key) {
            case "PopulationPeakDay" -> populationPeakDay = value;
            case "FollowSoundDistance" -> followSoundDistance = value;
            default -> throw new IllegalArgumentException("unknown int config key: " + key);
        }
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }
}
