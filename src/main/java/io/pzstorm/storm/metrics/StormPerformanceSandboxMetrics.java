package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Gauge;
import io.pzstorm.storm.connection.LoginQueueEarlyRelease;
import io.pzstorm.storm.connection.PeerSendBufferKickConfig;
import io.pzstorm.storm.connection.StormMaxPlayersConfig;
import io.pzstorm.storm.entity.EcsClassCache;
import io.pzstorm.storm.entity.StormEntityIndex;
import io.pzstorm.storm.entity.StormFluidContainerUpdate;
import io.pzstorm.storm.entity.UsingPlayerRegistry;
import io.pzstorm.storm.los.StormPlayerLos;
import io.pzstorm.storm.los.StormServerLosConfig;
import io.pzstorm.storm.los.ZombieVehicleOcclusion;
import io.pzstorm.storm.map.StormCellUnloadBudget;
import io.pzstorm.storm.patch.fixes.AnimalZoneContainment;
import io.pzstorm.storm.patch.fixes.HutchDirtRateFix;
import io.pzstorm.storm.patch.networking.GameServerTickRatePatch;
import io.pzstorm.storm.patch.networking.ServerLockFpsConfig;
import io.pzstorm.storm.patch.performance.AnimalLOSTickInterval;
import io.pzstorm.storm.patch.performance.InventoryItemSweepTickInterval;
import io.pzstorm.storm.patch.performance.IsoPhysicsObjectFpsConfig;
import io.pzstorm.storm.patch.performance.StormCellWarmingConfig;
import io.pzstorm.storm.patch.performance.StormZombieCullConfig;
import io.pzstorm.storm.patch.performance.VirtualAnimalTickInterval;
import io.pzstorm.storm.patch.performance.ZombieAuthTickInterval;
import io.pzstorm.storm.zombie.StormZombieTotalCap;

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
 *   <li>{@code storm_zombie_cull_threshold} — live value of vanilla's {@code
 *       ZombieConfig.ZombiesCountBeforeDelete}: zombies streamed to one connection before the
 *       surplus is culled. Vanilla default 300; 0 disables culling entirely. Read-only — Storm has
 *       no option of its own for this.
 *   <li>{@code storm_max_total_zombies} — world-wide ceiling on real zombies. Default 0 = disabled;
 *       vanilla has no global cap of any kind.
 *   <li>{@code storm_server_los_threads} — concurrent ServerLOS worker count. Default 1
 *       (single-threaded baseline); max 16. Pool always pre-allocates 15 helper threads regardless.
 *   <li>{@code storm_netdata_cap_ms} — per-spin wall-clock cap on {@code
 *       GameServer.mainLoopDealWithNetData} drain time (HIGH + player-update + vehicle combined).
 *       Default 90 ms; 0 disables. Stays at 0 until OnServerStarted fires the sandbox applier.
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

    private static final Gauge VIRTUAL_ANIMAL_TICK_INTERVAL =
            Gauge.builder()
                    .name("storm_virtual_animal_tick_interval")
                    .help(
                            "Configured stride for the AnimalZones.updateVirtualAnimals() pass on"
                                    + " the server (executing ticks compensate via"
                                    + " GameTime.perObjectMultiplier). Sourced from the"
                                    + " Storm.VirtualAnimalTickInterval sandbox option. Vanilla 1"
                                    + " (every tick); 0 = virtual-animal simulation frozen.")
                    .register(StormPrometheus.registry());

    private static final Gauge ZOMBIE_AUTH_TICK_INTERVAL =
            Gauge.builder()
                    .name("storm_zombie_auth_tick_interval")
                    .help(
                            "Configured per-zombie stride for the unowned-zombie ownership rescan"
                                    + " in NetworkZombieManager.updateAuth() on the server. Owned"
                                    + " zombies keep vanilla's 2s gate. Sourced from the"
                                    + " Storm.ZombieAuthTickInterval sandbox option. Vanilla 1"
                                    + " (every tick).")
                    .register(StormPrometheus.registry());

    private static final Gauge INVENTORY_ITEM_SWEEP_TICK_INTERVAL =
            Gauge.builder()
                    .name("storm_inventory_item_sweep_tick_interval")
                    .help(
                            "Configured stride for the orphaned-item GC sweep in"
                                    + " InventoryItemSystem.update() on the server. Sourced from"
                                    + " the Storm.InventoryItemSweepTickInterval sandbox option."
                                    + " Vanilla 1 (every tick).")
                    .register(StormPrometheus.registry());

    private static final Gauge ZOMBIE_CULL_THRESHOLD =
            Gauge.builder()
                    .name("storm_zombie_cull_threshold")
                    .help(
                            "Live value of the vanilla ZombieConfig.ZombiesCountBeforeDelete"
                                    + " option: zombies streamed to one connection before the"
                                    + " server culls the surplus. Vanilla default 300; 0 disables"
                                    + " culling entirely. Storm does not override this — set it"
                                    + " through the world setup UI or SandboxVars.lua.")
                    .register(StormPrometheus.registry());

    private static final Gauge MAX_TOTAL_ZOMBIES =
            Gauge.builder()
                    .name("storm_max_total_zombies")
                    .help(
                            "Configured world-wide ceiling on real zombies. Sourced from the"
                                    + " Storm.MaxTotalZombies sandbox option. Default 0 = disabled"
                                    + " (vanilla has no global cap at all). When non-zero and"
                                    + " storm_zombie_id_pool_size exceeds it, Storm deletes the"
                                    + " surplus — counted by"
                                    + " storm_zombies_total_cap_culled_total — restricted to"
                                    + " zombies no player can see.")
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

    private static final Gauge NETDATA_CAP_MS =
            Gauge.builder()
                    .name("storm_netdata_cap_ms")
                    .help(
                            "Per-spin wall-clock cap (milliseconds) on time spent inside"
                                    + " GameServer.mainLoopDealWithNetData. Sourced from the"
                                    + " Storm.NetDataCapMs sandbox option. Default 90; 0 disables"
                                    + " the cap. When the cap fires during a spin, subsequent"
                                    + " packets in that spin's HIGH/player-update/vehicle drain"
                                    + " are dropped (counted by pz_netdata_dropped_total) until"
                                    + " the next outer-loop iteration.")
                    .register(StormPrometheus.registry());

    private static final Gauge PEER_SEND_BUFFER_KICK_MB =
            Gauge.builder()
                    .name("storm_peer_send_buffer_kick_mb")
                    .help(
                            "Per-peer HIGH send-buffer threshold (megabytes) above which Storm"
                                    + " auto-disconnects the peer after the hold window elapses."
                                    + " Sourced from the Storm.PeerSendBufferKickMb sandbox"
                                    + " option. Default 20 MB; 0 disables the watchdog. When this"
                                    + " gauge is non-zero and a peer's"
                                    + " storm_peer_send_buffer_bytes{priority=\"high\"} stays above"
                                    + " (gauge_value * 1 MiB) for storm_peer_send_buffer_kick_hold_ticks"
                                    + " consecutive ticks, the peer is force-disconnected with"
                                    + " reason storm-send-buffer-overflow.")
                    .register(StormPrometheus.registry());

    private static final Gauge PEER_SEND_BUFFER_KICK_HOLD_TICKS =
            Gauge.builder()
                    .name("storm_peer_send_buffer_kick_hold_ticks")
                    .help(
                            "Consecutive server ticks a peer's HIGH send buffer must stay above"
                                    + " storm_peer_send_buffer_kick_mb before Storm"
                                    + " force-disconnects the peer. Sourced from the"
                                    + " Storm.PeerSendBufferKickHoldTicks sandbox option. Default"
                                    + " 50 ticks (= 5 seconds at vanilla 10 TPS). Has no effect"
                                    + " when the threshold gauge is 0 (watchdog disabled).")
                    .register(StormPrometheus.registry());

    private static final Gauge ZOMBIE_SIGHT_VEHICLE_FAST_PATH =
            Gauge.builder()
                    .name("storm_zombie_sight_vehicle_fast_path")
                    .help(
                            "Whether the chunk-windowed fast path for IsoZombie.isVehicleBetween"
                                    + " (zombie-sight vehicle occlusion) is active. Sourced from the"
                                    + " Storm.ZombieSightVehicleFastPath sandbox option. 1 = fast"
                                    + " path (default); 0 = vanilla whole-cell vehicle scan.")
                    .register(StormPrometheus.registry());

    private static final Gauge PLAYER_LOS_FAST_PATH =
            Gauge.builder()
                    .name("storm_player_los_fast_path")
                    .help(
                            "Whether the distance-culled, server-stripped fast path for"
                                    + " IsoPlayer.updateLOS() is active. Sourced from the"
                                    + " Storm.PlayerLosFastPath sandbox option. 1 = fast path"
                                    + " (default); 0 = vanilla whole-cell moving-object walk.")
                    .register(StormPrometheus.registry());

    private static final Gauge USING_PLAYER_SWEEP_FAST_PATH =
            Gauge.builder()
                    .name("storm_using_player_sweep_fast_path")
                    .help(
                            "Whether the registry-backed sweep for"
                                    + " UsingPlayerUpdateSystem.update() is active. Sourced from"
                                    + " the Storm.UsingPlayerSweepFastPath sandbox option. 1 ="
                                    + " registry sweep (default); 0 = vanilla full iso-bucket"
                                    + " scan.")
                    .register(StormPrometheus.registry());

    private static final Gauge FLUID_CONTAINER_UPDATE_FAST_PATH =
            Gauge.builder()
                    .name("storm_fluid_container_update_fast_path")
                    .help(
                            "Whether the hoisted/reordered fast path for"
                                    + " FluidContainerUpdateSystem.updateSimulation() is active."
                                    + " Sourced from the Storm.FluidContainerUpdateFastPath sandbox"
                                    + " option. 1 = fast path (default); 0 = vanilla per-entity"
                                    + " climate/sandbox re-reads and fluid-list scans.")
                    .register(StormPrometheus.registry());

    private static final Gauge ECS_CLASS_CACHE =
            Gauge.builder()
                    .name("storm_ecs_class_cache")
                    .help(
                            "Whether the ClassValue memoization of ECSComponent.getECSClass(Class)"
                                    + " is active. Sourced from the Storm.EcsClassCache sandbox"
                                    + " option. 1 = memoized (default); 0 = vanilla superclass walk"
                                    + " on every component lookup.")
                    .register(StormPrometheus.registry());

    private static final Gauge HUTCH_DIRT_RATE_PERCENT =
            Gauge.builder()
                    .name("storm_hutch_dirt_rate_percent")
                    .help(
                            "Percentage of the intended (metagame) hutch dirt rate applied while a"
                                    + " chicken coop / rabbit hutch is loaded, replacing vanilla's"
                                    + " tick-rate-proportional accrual. Sourced from the"
                                    + " Storm.HutchDirtRatePercent sandbox option. 100 = the"
                                    + " game-hour rate IsoHutch.doMeta intends (default); 0 = dirt"
                                    + " never accrues while loaded.")
                    .register(StormPrometheus.registry());

    private static final Gauge ANIMAL_ZONE_CONTAINMENT =
            Gauge.builder()
                    .name("storm_animal_zone_containment")
                    .help(
                            "Whether animals are held inside the player-placed animal zone they"
                                    + " belong to. Sourced from the Storm.AnimalZoneContainment"
                                    + " sandbox option. 1 = contained animals never break through"
                                    + " or path outside their zone (default); 0 = vanilla, where"
                                    + " hungry livestock paths through and destroys player-built"
                                    + " pen walls.")
                    .register(StormPrometheus.registry());

    private static final Gauge ANIMAL_ZONE_LEASH_DISTANCE =
            Gauge.builder()
                    .name("storm_animal_zone_leash_distance")
                    .help(
                            "How far outside an animal zone, in tiles, a non-wild animal is still"
                                    + " treated as belonging to it and walked back in. Sourced from"
                                    + " the Storm.AnimalZoneLeashDistance sandbox option. 0 = only"
                                    + " animals standing inside a zone are contained.")
                    .register(StormPrometheus.registry());

    private static final Gauge CELL_UNLOAD_BUDGET_PER_TICK =
            Gauge.builder()
                    .name("storm_cell_unload_budget_per_tick")
                    .help(
                            "Maximum number of stale server cells ServerMap.postupdate may"
                                    + " destructively unload per tick; stale cells beyond the"
                                    + " budget stay loaded and are re-evaluated next tick. Sourced"
                                    + " from the Storm.CellUnloadBudgetPerTick sandbox option."
                                    + " Default 2; 0 = vanilla (unload every stale cell in one"
                                    + " tick).")
                    .register(StormPrometheus.registry());

    private static final Gauge ENTITY_REMOVE_FAST_PATH =
            Gauge.builder()
                    .name("storm_entity_remove_fast_path")
                    .help(
                            "Whether the O(1) indexed removal from the engine's global entity"
                                    + " array is active. Sourced from the"
                                    + " Storm.EntityRemoveFastPath sandbox option. 1 = indexed"
                                    + " swap-with-last removal (default); 0 = vanilla linear"
                                    + " identity scan of the whole array.")
                    .register(StormPrometheus.registry());

    private static final Gauge MAX_PLAYERS_OVERRIDE_ENABLED =
            Gauge.builder()
                    .name("storm_max_players_override_enabled")
                    .help(
                            "Whether the Storm.OverrideMaxPlayers sandbox option is active,"
                                    + " replacing the .ini MaxPlayers value with"
                                    + " storm_max_players_override everywhere the server reads it"
                                    + " (login gate, login queue, co-op join, server browser info)."
                                    + " 1 = override active; 0 = the .ini value is used untouched"
                                    + " (default).")
                    .register(StormPrometheus.registry());

    private static final Gauge MAX_PLAYERS_OVERRIDE =
            Gauge.builder()
                    .name("storm_max_players_override")
                    .help(
                            "Configured Storm.MaxPlayers sandbox value — the player-count ceiling"
                                    + " used instead of the .ini MaxPlayers while"
                                    + " storm_max_players_override_enabled is 1 (ignored while 0)."
                                    + " Default 100; range 1-500. Vanilla hard-caps the .ini value"
                                    + " at 100 — values above it only take effect through this"
                                    + " override.")
                    .register(StormPrometheus.registry());

    private static final Gauge LOGIN_QUEUE_MAX_CONCURRENT_LOADERS =
            Gauge.builder()
                    .name("storm_login_queue_max_concurrent_loaders")
                    .help(
                            "Maximum joiners allowed to be loading into the server at once —"
                                    + " released loaders plus the login-queue slot-holder. Sourced"
                                    + " from the Storm.LoginQueueMaxConcurrentLoaders sandbox"
                                    + " option. 1 = vanilla admission (default): the slot is held"
                                    + " until LoginQueueDone and never released early.")
                    .register(StormPrometheus.registry());

    private static final Gauge CELL_WARMING_ENABLED =
            Gauge.builder()
                    .name("storm_cell_warming_enabled")
                    .help(
                            "Whether cell warming is enabled — stale server cells are kept resident"
                                    + " with their world-system bindings detached instead of being"
                                    + " destructively unloaded. Sourced from the Storm.KeepCellsWarm"
                                    + " sandbox option. 0 = vanilla unload (default). After a live"
                                    + " 0, storm_cell_warm_count drains to zero over the following"
                                    + " ticks before postupdate returns to vanilla.")
                    .register(StormPrometheus.registry());

    private static final Gauge MAX_WARM_CELLS =
            Gauge.builder()
                    .name("storm_max_warm_cells")
                    .help(
                            "Maximum cells held warm at once; above it the least-recently-warmed"
                                    + " cells are evicted through the vanilla unload path. Sourced"
                                    + " from the Storm.MaxWarmCells sandbox option. Default 128; 0 ="
                                    + " unbounded.")
                    .register(StormPrometheus.registry());

    static {
        SERVER_TICK_INTERVAL_SECONDS.set(GameServerTickRatePatch.DEFAULT_TICK_INTERVAL_MS / 1000.0);
        SERVER_LOCK_FPS.set(ServerLockFpsConfig.DEFAULT_LOCK_FPS);
        ISO_PHYSICS_SERVER_FPS.set(IsoPhysicsObjectFpsConfig.DEFAULT_PHYSICS_FPS);
        ANIMAL_LOS_TICK_INTERVAL.set(AnimalLOSTickInterval.DEFAULT_TICK_INTERVAL);
        VIRTUAL_ANIMAL_TICK_INTERVAL.set(VirtualAnimalTickInterval.DEFAULT_TICK_INTERVAL);
        ZOMBIE_AUTH_TICK_INTERVAL.set(ZombieAuthTickInterval.DEFAULT_TICK_INTERVAL);
        INVENTORY_ITEM_SWEEP_TICK_INTERVAL.set(
                InventoryItemSweepTickInterval.DEFAULT_TICK_INTERVAL);
        ZOMBIE_CULL_THRESHOLD.set(StormZombieCullConfig.VANILLA_DEFAULT);
        MAX_TOTAL_ZOMBIES.set(StormZombieTotalCap.DEFAULT_MAX_TOTAL);
        SERVER_LOS_THREADS.set(StormServerLosConfig.DEFAULT_THREADS);
        NETDATA_CAP_MS.set(0);
        PEER_SEND_BUFFER_KICK_MB.set(PeerSendBufferKickConfig.DEFAULT_MB);
        PEER_SEND_BUFFER_KICK_HOLD_TICKS.set(PeerSendBufferKickConfig.DEFAULT_HOLD_TICKS);
        ZOMBIE_SIGHT_VEHICLE_FAST_PATH.set(ZombieVehicleOcclusion.DEFAULT_ENABLED ? 1 : 0);
        PLAYER_LOS_FAST_PATH.set(StormPlayerLos.DEFAULT_ENABLED ? 1 : 0);
        USING_PLAYER_SWEEP_FAST_PATH.set(UsingPlayerRegistry.DEFAULT_ENABLED ? 1 : 0);
        FLUID_CONTAINER_UPDATE_FAST_PATH.set(StormFluidContainerUpdate.DEFAULT_ENABLED ? 1 : 0);
        ECS_CLASS_CACHE.set(EcsClassCache.DEFAULT_ENABLED ? 1 : 0);
        HUTCH_DIRT_RATE_PERCENT.set(HutchDirtRateFix.DEFAULT_RATE_PERCENT);
        ANIMAL_ZONE_CONTAINMENT.set(AnimalZoneContainment.DEFAULT_ENABLED ? 1 : 0);
        ANIMAL_ZONE_LEASH_DISTANCE.set(AnimalZoneContainment.DEFAULT_LEASH_DISTANCE);
        CELL_UNLOAD_BUDGET_PER_TICK.set(StormCellUnloadBudget.DEFAULT_BUDGET);
        ENTITY_REMOVE_FAST_PATH.set(StormEntityIndex.DEFAULT_ENABLED ? 1 : 0);
        MAX_PLAYERS_OVERRIDE_ENABLED.set(StormMaxPlayersConfig.DEFAULT_OVERRIDE_ENABLED ? 1 : 0);
        MAX_PLAYERS_OVERRIDE.set(StormMaxPlayersConfig.DEFAULT_MAX_PLAYERS);
        LOGIN_QUEUE_MAX_CONCURRENT_LOADERS.set(
                LoginQueueEarlyRelease.DEFAULT_MAX_CONCURRENT_LOADERS);
        CELL_WARMING_ENABLED.set(StormCellWarmingConfig.DEFAULT_ENABLED ? 1 : 0);
        MAX_WARM_CELLS.set(StormCellWarmingConfig.DEFAULT_MAX_WARM_CELLS);
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

    public static void setVirtualAnimalTickInterval(int interval) {
        VIRTUAL_ANIMAL_TICK_INTERVAL.set(interval);
    }

    public static void setZombieAuthTickInterval(int interval) {
        ZOMBIE_AUTH_TICK_INTERVAL.set(interval);
    }

    public static void setInventoryItemSweepTickInterval(int interval) {
        INVENTORY_ITEM_SWEEP_TICK_INTERVAL.set(interval);
    }

    public static void setZombieCullThreshold(int threshold) {
        ZOMBIE_CULL_THRESHOLD.set(threshold);
    }

    public static void setMaxTotalZombies(int max) {
        MAX_TOTAL_ZOMBIES.set(max);
    }

    public static void setServerLosThreads(int threads) {
        SERVER_LOS_THREADS.set(threads);
    }

    public static void setNetDataCapMs(int ms) {
        NETDATA_CAP_MS.set(ms);
    }

    public static void setPeerSendBufferKickMb(int mb) {
        PEER_SEND_BUFFER_KICK_MB.set(mb);
    }

    public static void setPeerSendBufferKickHoldTicks(int ticks) {
        PEER_SEND_BUFFER_KICK_HOLD_TICKS.set(ticks);
    }

    public static void setZombieSightVehicleFastPath(boolean enabled) {
        ZOMBIE_SIGHT_VEHICLE_FAST_PATH.set(enabled ? 1 : 0);
    }

    public static void setPlayerLosFastPath(boolean enabled) {
        PLAYER_LOS_FAST_PATH.set(enabled ? 1 : 0);
    }

    public static void setUsingPlayerSweepFastPath(boolean enabled) {
        USING_PLAYER_SWEEP_FAST_PATH.set(enabled ? 1 : 0);
    }

    public static void setFluidContainerUpdateFastPath(boolean enabled) {
        FLUID_CONTAINER_UPDATE_FAST_PATH.set(enabled ? 1 : 0);
    }

    public static void setEcsClassCache(boolean enabled) {
        ECS_CLASS_CACHE.set(enabled ? 1 : 0);
    }

    public static void setAnimalZoneContainment(boolean enabled) {
        ANIMAL_ZONE_CONTAINMENT.set(enabled ? 1 : 0);
    }

    public static void setAnimalZoneLeashDistance(int tiles) {
        ANIMAL_ZONE_LEASH_DISTANCE.set(tiles);
    }

    public static void setCellUnloadBudgetPerTick(int budget) {
        CELL_UNLOAD_BUDGET_PER_TICK.set(budget);
    }

    public static void setHutchDirtRatePercent(int percent) {
        HUTCH_DIRT_RATE_PERCENT.set(percent);
    }

    public static void setEntityRemoveFastPath(boolean enabled) {
        ENTITY_REMOVE_FAST_PATH.set(enabled ? 1 : 0);
    }

    public static void setMaxPlayersOverrideEnabled(boolean enabled) {
        MAX_PLAYERS_OVERRIDE_ENABLED.set(enabled ? 1 : 0);
    }

    public static void setMaxPlayersOverride(int maxPlayers) {
        MAX_PLAYERS_OVERRIDE.set(maxPlayers);
    }

    public static void setLoginQueueMaxConcurrentLoaders(int loaders) {
        LOGIN_QUEUE_MAX_CONCURRENT_LOADERS.set(loaders);
    }

    public static void setCellWarmingEnabled(boolean enabled) {
        CELL_WARMING_ENABLED.set(enabled ? 1 : 0);
    }

    public static void setMaxWarmCells(int cells) {
        MAX_WARM_CELLS.set(cells);
    }
}
