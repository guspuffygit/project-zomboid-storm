package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Gauge;
import io.pzstorm.storm.los.StormServerLosConfig;
import io.pzstorm.storm.patch.networking.GameServerTickRatePatch;
import io.pzstorm.storm.patch.networking.ServerLockFpsConfig;
import io.pzstorm.storm.patch.performance.AnimalLOSTickInterval;
import io.pzstorm.storm.patch.performance.IsoPhysicsObjectFpsConfig;
import io.pzstorm.storm.patch.performance.StormChunkRecalcConfig;
import io.pzstorm.storm.patch.performance.StormZombieCullConfig;

/**
 * Live gauges reflecting Storm's performance knobs.
 *
 * <p>Each gauge tracks the current effective value of its corresponding controller — sandbox-option
 * load or test override flows through the controller's live setter, which in turn pushes the new
 * value here. The static block initializes every gauge to its vanilla default so scrapes return a
 * sane value before the sandbox applier (or any other setter) has run.
 *
 * <ul>
 *   <li>{@code storm_server_tick_interval_seconds} — current server main-loop tick interval.
 *       Vanilla 0.1 (100&nbsp;ms / 10&nbsp;TPS).
 *   <li>{@code storm_server_lock_fps} — current {@code PerformanceSettings.getLockFPS()} value on
 *       the server. Vanilla 10.
 *   <li>{@code storm_iso_physics_server_fps} — current FPS scalar inside {@code
 *       IsoPhysicsObject.update()} on the server. Vanilla 10.
 *   <li>{@code storm_animal_los_tick_interval} — per-animal stride for {@code
 *       IsoAnimal.updateLOS()} on the server. Vanilla 1 (every tick); 0 = LOS disabled.
 *   <li>{@code storm_zombie_cull_threshold} — Storm-controlled cull threshold. Default 500 (matches
 *       vanilla cap); 0 disables culling entirely.
 *   <li>{@code storm_server_los_threads} — concurrent ServerLOS worker count. Default 1
 *       (single-threaded baseline); max 16. Pool always pre-allocates 15 helper threads regardless.
 *   <li>{@code storm_chunk_recalc_threads} — active ServerChunkLoader recalc worker count. Default
 *       1 (vanilla single worker); max 16. Pool always pre-allocates all 16 workers (1 vanilla + 15
 *       Storm extras); this only controls how many drain the recalc queue.
 * </ul>
 */
public final class StormPerformanceSandboxMetrics {

    private static final Gauge SERVER_TICK_INTERVAL_SECONDS =
            Gauge.builder()
                    .name("storm_server_tick_interval_seconds")
                    .help(
                            "Configured server main-loop tick interval in seconds (gate that"
                                    + " controls server TPS). Sourced from the unified Storm.ServerFps"
                                    + " sandbox option (intervalMs = round(1000 / fps)). Vanilla 0.1"
                                    + " (100ms / 10 TPS).")
                    .register(StormPrometheus.registry());

    private static final Gauge SERVER_LOCK_FPS =
            Gauge.builder()
                    .name("storm_server_lock_fps")
                    .help(
                            "Configured PerformanceSettings.getLockFPS() value on the server."
                                    + " Sourced from the unified Storm.ServerFps sandbox option."
                                    + " Vanilla 10.")
                    .register(StormPrometheus.registry());

    private static final Gauge ISO_PHYSICS_SERVER_FPS =
            Gauge.builder()
                    .name("storm_iso_physics_server_fps")
                    .help(
                            "Configured FPS scalar used inside IsoPhysicsObject.update() on the"
                                    + " server. Sourced from the unified Storm.ServerFps sandbox"
                                    + " option. Vanilla 10.")
                    .register(StormPrometheus.registry());

    private static final Gauge ANIMAL_LOS_TICK_INTERVAL =
            Gauge.builder()
                    .name("storm_animal_los_tick_interval")
                    .help(
                            "Configured per-animal stride for IsoAnimal.updateLOS() on the server."
                                    + " Sourced from the Storm.AnimalLOSTickInterval sandbox option."
                                    + " Vanilla 1 (every tick); 0 = LOS disabled.")
                    .register(StormPrometheus.registry());

    private static final Gauge ZOMBIE_CULL_THRESHOLD =
            Gauge.builder()
                    .name("storm_zombie_cull_threshold")
                    .help(
                            "Storm-controlled zombie cull threshold. Sourced from the"
                                    + " Storm.ZombieCullThreshold sandbox option. Default 500 (matches"
                                    + " vanilla cap); 0 disables culling entirely.")
                    .register(StormPrometheus.registry());

    private static final Gauge SERVER_LOS_THREADS =
            Gauge.builder()
                    .name("storm_server_los_threads")
                    .help(
                            "Concurrent ServerLOS worker count (slots receiving per-player scans"
                                    + " each tick). Sourced from the Storm.ServerLosThreads sandbox"
                                    + " option. Default 1 (single-threaded baseline); max 16. The pool"
                                    + " always pre-allocates 15 helper threads regardless of this"
                                    + " value.")
                    .register(StormPrometheus.registry());

    private static final Gauge CHUNK_RECALC_THREADS =
            Gauge.builder()
                    .name("storm_chunk_recalc_threads")
                    .help(
                            "Configured ServerChunkLoader recalc worker count (active slots"
                                    + " draining the recalc queue). Sourced from the"
                                    + " Storm.ChunkRecalcThreads sandbox option. Default 1 (vanilla"
                                    + " single worker); max 16. The pool always pre-allocates all"
                                    + " 16 workers regardless (1 vanilla + 15 Storm extras); this"
                                    + " only controls how many are gated through to toThread.take.")
                    .register(StormPrometheus.registry());

    static {
        SERVER_TICK_INTERVAL_SECONDS.set(GameServerTickRatePatch.DEFAULT_TICK_INTERVAL_MS / 1000.0);
        SERVER_LOCK_FPS.set(ServerLockFpsConfig.DEFAULT_LOCK_FPS);
        ISO_PHYSICS_SERVER_FPS.set(IsoPhysicsObjectFpsConfig.DEFAULT_PHYSICS_FPS);
        ANIMAL_LOS_TICK_INTERVAL.set(AnimalLOSTickInterval.DEFAULT_TICK_INTERVAL);
        ZOMBIE_CULL_THRESHOLD.set(StormZombieCullConfig.DEFAULT_THRESHOLD);
        SERVER_LOS_THREADS.set(StormServerLosConfig.DEFAULT_THREADS);
        CHUNK_RECALC_THREADS.set(StormChunkRecalcConfig.DEFAULT_THREADS);
    }

    private StormPerformanceSandboxMetrics() {}

    public static void setServerTickIntervalMs(long ms) {
        SERVER_TICK_INTERVAL_SECONDS.set(ms / 1000.0);
    }

    public static void setServerLockFps(int fps) {
        SERVER_LOCK_FPS.set(fps);
    }

    public static void setIsoPhysicsServerFps(int fps) {
        ISO_PHYSICS_SERVER_FPS.set(fps);
    }

    public static void setAnimalLOSTickInterval(int interval) {
        ANIMAL_LOS_TICK_INTERVAL.set(interval);
    }

    public static void setZombieCullThreshold(int threshold) {
        ZOMBIE_CULL_THRESHOLD.set(threshold);
    }

    public static void setServerLosThreads(int threads) {
        SERVER_LOS_THREADS.set(threads);
    }

    public static void setChunkRecalcThreads(int threads) {
        CHUNK_RECALC_THREADS.set(threads);
    }
}
