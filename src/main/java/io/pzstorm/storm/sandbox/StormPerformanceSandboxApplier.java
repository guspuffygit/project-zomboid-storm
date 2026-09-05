package io.pzstorm.storm.sandbox;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.advice.gameserverstalledconnections.StalledConnectionReaper;
import io.pzstorm.storm.advice.netdatadraincap.MainLoopDrainCap;
import io.pzstorm.storm.connection.LoginQueueEarlyRelease;
import io.pzstorm.storm.connection.PeerSendBufferKickConfig;
import io.pzstorm.storm.connection.StormMaxPlayersConfig;
import io.pzstorm.storm.entity.EcsClassCache;
import io.pzstorm.storm.entity.StormEntityIndex;
import io.pzstorm.storm.entity.StormFluidContainerUpdate;
import io.pzstorm.storm.entity.UsingPlayerRegistry;
import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.OnServerStartedEvent;
import io.pzstorm.storm.event.zomboid.OnSandboxOptionsUpdateEvent;
import io.pzstorm.storm.los.StormPlayerLos;
import io.pzstorm.storm.los.StormServerLosConfig;
import io.pzstorm.storm.los.ZombieVehicleOcclusion;
import io.pzstorm.storm.map.StormCellUnloadBudget;
import io.pzstorm.storm.patch.fixes.AnimalZoneContainment;
import io.pzstorm.storm.patch.fixes.HutchDirtRateFix;
import io.pzstorm.storm.patch.networking.GameServerTickRatePatch.UpdateLimitFactory;
import io.pzstorm.storm.patch.networking.ServerFpsConfig;
import io.pzstorm.storm.patch.performance.AnimalLOSTickInterval;
import io.pzstorm.storm.patch.performance.ImportantAreasPolicy;
import io.pzstorm.storm.patch.performance.InventoryItemSweepTickInterval;
import io.pzstorm.storm.patch.performance.StormCellWarmingConfig;
import io.pzstorm.storm.patch.performance.StormZombieCullConfig;
import io.pzstorm.storm.patch.performance.VirtualAnimalTickInterval;
import io.pzstorm.storm.patch.performance.ZombieAuthTickInterval;
import io.pzstorm.storm.patch.performance.ZombieRainWanderInterval;
import io.pzstorm.storm.vehicles.StormVehicleAlphaCheckSkip;
import io.pzstorm.storm.vehicles.StormVehicleSoundRelevance;
import io.pzstorm.storm.zombie.StormZombieTotalCap;
import zombie.SandboxOptions;
import zombie.core.znet.SteamGameServer;
import zombie.core.znet.SteamUtils;
import zombie.network.GameServer;
import zombie.network.ServerOptions;

/**
 * Reads Storm's performance sandbox options at {@code OnServerStarted} and pushes them through the
 * existing live setters. {@code Storm.ServerFps} feeds {@link ServerFpsConfig#applyUnifiedFps(int)}
 * (which sets tick interval, lockFps, and IsoPhysicsObject fps); the remaining options ({@link
 * AnimalLOSTickInterval}, {@link StormServerLosConfig}, ...) are each 1:1 with a sandbox option.
 * {@link StormZombieCullConfig} is the exception — it owns no option and is only republished to its
 * gauge here.
 *
 * <p>This runs only on the dedicated server — the event also fires on the client when a hosted coop
 * server starts, but the sandbox knobs here only make sense for the authoritative server JVM.
 */
public final class StormPerformanceSandboxApplier {

    public static final String OPT_SERVER_FPS = "Storm.ServerFps";
    public static final String OPT_ANIMAL_LOS_TICK_INTERVAL = "Storm.AnimalLOSTickInterval";
    public static final String OPT_VIRTUAL_ANIMAL_TICK_INTERVAL = "Storm.VirtualAnimalTickInterval";
    public static final String OPT_ZOMBIE_AUTH_TICK_INTERVAL = "Storm.ZombieAuthTickInterval";
    public static final String OPT_ZOMBIE_RAIN_WANDER_PERCENT = "Storm.ZombieRainWanderPercent";
    public static final String OPT_IMPORTANT_AREAS_MAXIMUM = "Storm.ImportantAreasMaximum";
    public static final String OPT_INVENTORY_ITEM_SWEEP_TICK_INTERVAL =
            "Storm.InventoryItemSweepTickInterval";
    public static final String OPT_MAX_TOTAL_ZOMBIES = "Storm.MaxTotalZombies";
    public static final String OPT_SERVER_LOS_THREADS = "Storm.ServerLosThreads";
    public static final String OPT_NETDATA_CAP_MS = "Storm.NetDataCapMs";
    public static final String OPT_PEER_SEND_BUFFER_KICK_MB = "Storm.PeerSendBufferKickMb";
    public static final String OPT_PEER_SEND_BUFFER_KICK_HOLD_TICKS =
            "Storm.PeerSendBufferKickHoldTicks";
    public static final String OPT_REAP_STALLED_CONNECTION_SECONDS =
            "Storm.ReapStalledConnectionSeconds";
    public static final String OPT_ZOMBIE_SIGHT_VEHICLE_FAST_PATH =
            "Storm.ZombieSightVehicleFastPath";
    public static final String OPT_PLAYER_LOS_FAST_PATH = "Storm.PlayerLosFastPath";
    public static final String OPT_USING_PLAYER_SWEEP_FAST_PATH = "Storm.UsingPlayerSweepFastPath";
    public static final String OPT_FLUID_CONTAINER_UPDATE_FAST_PATH =
            "Storm.FluidContainerUpdateFastPath";
    public static final String OPT_ECS_CLASS_CACHE = "Storm.EcsClassCache";
    public static final String OPT_CELL_UNLOAD_BUDGET_PER_TICK = "Storm.CellUnloadBudgetPerTick";
    public static final String OPT_HUTCH_DIRT_RATE_PERCENT = "Storm.HutchDirtRatePercent";
    public static final String OPT_ANIMAL_ZONE_CONTAINMENT = "Storm.AnimalZoneContainment";
    public static final String OPT_ANIMAL_ZONE_LEASH_DISTANCE = "Storm.AnimalZoneLeashDistance";
    public static final String OPT_ENTITY_REMOVE_FAST_PATH = "Storm.EntityRemoveFastPath";
    public static final String OPT_VEHICLE_ALPHA_CHECK_SKIP = "Storm.VehicleAlphaCheckSkip";
    public static final String OPT_VEHICLE_SOUND_RELEVANCE_FAST_PATH =
            "Storm.VehicleSoundRelevanceFastPath";
    public static final String OPT_OVERRIDE_MAX_PLAYERS = "Storm.OverrideMaxPlayers";
    public static final String OPT_MAX_PLAYERS = "Storm.MaxPlayers";
    public static final String OPT_LOGIN_QUEUE_MAX_CONCURRENT_LOADERS =
            "Storm.LoginQueueMaxConcurrentLoaders";
    public static final String OPT_KEEP_CELLS_WARM = "Storm.KeepCellsWarm";
    public static final String OPT_MAX_WARM_CELLS = "Storm.MaxWarmCells";

    /** Set on the first legitimately-early {@link #applyServerFps()} skip at boot. */
    private static boolean serverFpsSkippedOnce;

    private StormPerformanceSandboxApplier() {}

    @SubscribeEvent
    public static void onServerStarted(OnServerStartedEvent event) {
        applyAll();
    }

    @SubscribeEvent
    public static void onSandboxOptionsUpdate(OnSandboxOptionsUpdateEvent event) {
        applyAll();
    }

    /**
     * Reads every Storm sandbox option and pushes it through the corresponding live setter. Used at
     * {@code OnServerStarted} for the initial load, and re-invoked on {@link
     * OnSandboxOptionsUpdateEvent} after an admin pushes new options at runtime so the Prometheus
     * gauges (and the underlying config classes) reflect the change without a restart.
     */
    public static void applyAll() {
        if (!GameServer.server) {
            return;
        }
        applyServerFps();
        applyAnimalLosTickInterval();
        applyVirtualAnimalTickInterval();
        applyZombieAuthTickInterval();
        applyZombieRainWanderPercent();
        applyImportantAreasMaximum();
        applyInventoryItemSweepTickInterval();
        refreshZombieCullThreshold();
        applyMaxTotalZombies();
        applyServerLosThreads();
        applyNetDataCapMs();
        applyPeerSendBufferKickMb();
        applyPeerSendBufferKickHoldTicks();
        applyReapStalledConnectionSeconds();
        applyZombieSightVehicleFastPath();
        applyPlayerLosFastPath();
        applyUsingPlayerSweepFastPath();
        applyFluidContainerUpdateFastPath();
        applyEcsClassCache();
        applyCellUnloadBudgetPerTick();
        applyHutchDirtRatePercent();
        applyAnimalZoneContainment();
        applyAnimalZoneLeashDistance();
        applyEntityRemoveFastPath();
        applyVehicleAlphaCheckSkip();
        applyVehicleSoundRelevanceFastPath();
        applyMaxPlayersOverride();
        applyLoginQueueMaxConcurrentLoaders();
        applyMaxWarmCells();
        applyKeepCellsWarm();
    }

    /**
     * Pushes {@link #OPT_MAX_WARM_CELLS} through {@link
     * StormCellWarmingConfig#setMaxWarmCells(int)} — the bound on the warm set. Applied before the
     * enable flag so a first-time enable starts with the intended cap rather than the compiled-in
     * default for one tick.
     */
    private static void applyMaxWarmCells() {
        Integer value = readIntOption(OPT_MAX_WARM_CELLS);
        if (value == null) {
            return;
        }
        StormCellWarmingConfig.setMaxWarmCells(value);
    }

    /**
     * Pushes {@link #OPT_KEEP_CELLS_WARM} through {@link
     * StormCellWarmingConfig#setEnabled(boolean)}. Both directions are live: on starts warming at
     * the next postupdate; off leaves {@code StormCellWarmer} owning postupdate in drain mode until
     * every warm cell has gone through the eviction path, then hands the body back to the unload
     * budget / vanilla.
     */
    private static void applyKeepCellsWarm() {
        Boolean value = readBooleanOption(OPT_KEEP_CELLS_WARM);
        if (value == null) {
            return;
        }
        StormCellWarmingConfig.setEnabled(value);
    }

    /**
     * Pushes {@link #OPT_LOGIN_QUEUE_MAX_CONCURRENT_LOADERS} through {@link
     * LoginQueueEarlyRelease#setMaxConcurrentLoaders(int)} — total joiners allowed to be loading
     * into the server at once (released loaders plus the login-queue slot-holder). 1 restores
     * vanilla admission: the slot is held until {@code LoginQueueDone}, never released early.
     */
    private static void applyLoginQueueMaxConcurrentLoaders() {
        Integer value = readIntOption(OPT_LOGIN_QUEUE_MAX_CONCURRENT_LOADERS);
        if (value == null) {
            return;
        }
        LoginQueueEarlyRelease.setMaxConcurrentLoaders(value);
    }

    /**
     * Pushes {@link #OPT_OVERRIDE_MAX_PLAYERS} and {@link #OPT_MAX_PLAYERS} through {@link
     * StormMaxPlayersConfig#setOverride(boolean, int)} — the live replacement for the {@code .ini}
     * {@code MaxPlayers} value. Public because {@code
     * GameServerConnectionCapPatch.UdpEngineFactory} also invokes it at {@code UdpEngine}
     * construction time: sandbox vars are already loaded ({@code doMinimumInit}) but {@code
     * OnServerStarted} has not fired yet, and the boot-time RakNet connection cap must account for
     * an enabled override.
     *
     * <p>The Steam-browser re-push lives here (not in the config setter) so it only ever runs in a
     * real server JVM — {@code StormMaxPlayersConfig} must stay free of {@code zombie.*}
     * references, because unit tests call the setter in a bare JVM where {@code
     * ServerOptions.&lt;clinit&gt;} fails and stays poisoned for later tests in the same JVM.
     */
    public static void applyMaxPlayersOverride() {
        Boolean enabled = readBooleanOption(OPT_OVERRIDE_MAX_PLAYERS);
        Integer maxPlayers = readIntOption(OPT_MAX_PLAYERS);
        if (enabled == null || maxPlayers == null) {
            return;
        }
        boolean wasEnabled = StormMaxPlayersConfig.isOverrideEnabled();
        int wasValue = StormMaxPlayersConfig.getConfiguredMaxPlayers();
        int clamped = StormMaxPlayersConfig.setOverride(enabled, maxPlayers);
        boolean effectiveChanged = enabled != wasEnabled || (enabled && clamped != wasValue);
        if (effectiveChanged) {
            pushEffectiveMaxPlayers(enabled, clamped);
        }
    }

    /**
     * Reports the new effective ceiling and mirrors it to the Steam server browser — vanilla calls
     * {@code SteamGameServer.SetMaxPlayerCount} exactly once at boot, before sandbox vars are even
     * loaded, so without this re-push the browser would keep advertising the {@code .ini} value.
     */
    private static void pushEffectiveMaxPlayers(boolean overrideEnabled, int overrideValue) {
        int effective;
        try {
            effective = ServerOptions.getInstance().getMaxPlayers();
        } catch (Throwable t) {
            LOGGER.warn(
                    "Storm: could not read the effective MaxPlayers after an override change", t);
            return;
        }
        LOGGER.info(
                "Storm: effective max player count is now {} (override {})",
                effective,
                overrideEnabled ? "enabled, value " + overrideValue : "disabled, .ini value");
        try {
            if (SteamUtils.isSteamModeEnabled()) {
                SteamGameServer.SetMaxPlayerCount(effective);
            }
        } catch (Throwable t) {
            LOGGER.warn(
                    "Storm: could not push the max player count {} to the Steam server browser",
                    effective,
                    t);
        }
    }

    /**
     * Pushes {@link #OPT_CELL_UNLOAD_BUDGET_PER_TICK} through {@link
     * StormCellUnloadBudget#setBudgetPerTick(int)} — the per-tick cap on destructive server-cell
     * unloads in {@code ServerMap.postupdate}. 0 restores vanilla unload-everything-now behavior.
     */
    private static void applyCellUnloadBudgetPerTick() {
        Integer value = readIntOption(OPT_CELL_UNLOAD_BUDGET_PER_TICK);
        if (value == null) {
            return;
        }
        StormCellUnloadBudget.setBudgetPerTick(value);
    }

    /**
     * Pushes {@link #OPT_HUTCH_DIRT_RATE_PERCENT} through {@link
     * HutchDirtRateFix#setRatePercent(int)} — the percentage of the intended (metagame) hutch dirt
     * rate applied while a coop is loaded. 100 = the {@code IsoHutch.doMeta} rate; 0 = dirt never
     * accrues while loaded.
     */
    private static void applyHutchDirtRatePercent() {
        Integer value = readIntOption(OPT_HUTCH_DIRT_RATE_PERCENT);
        if (value == null) {
            return;
        }
        HutchDirtRateFix.setRatePercent(value);
    }

    /**
     * Pushes {@link #OPT_ANIMAL_ZONE_CONTAINMENT} through {@link
     * AnimalZoneContainment#setEnabled(boolean)} — the kill switch for holding livestock inside the
     * player-placed animal zone it belongs to. {@code false} restores vanilla, where a hungry
     * animal paths through and destroys player-built pen walls.
     */
    private static void applyAnimalZoneContainment() {
        Boolean value = readBooleanOption(OPT_ANIMAL_ZONE_CONTAINMENT);
        if (value == null) {
            return;
        }
        AnimalZoneContainment.setEnabled(value);
    }

    /**
     * Pushes {@link #OPT_ANIMAL_ZONE_LEASH_DISTANCE} through {@link
     * AnimalZoneContainment#setLeashDistance(int)} — how far outside a zone a non-wild animal is
     * still treated as belonging to it, and therefore walked back in. 0 contains only animals
     * standing inside a zone.
     */
    private static void applyAnimalZoneLeashDistance() {
        Integer value = readIntOption(OPT_ANIMAL_ZONE_LEASH_DISTANCE);
        if (value == null) {
            return;
        }
        AnimalZoneContainment.setLeashDistance(value);
    }

    /**
     * Pushes {@link #OPT_ENTITY_REMOVE_FAST_PATH} through {@link
     * StormEntityIndex#setEnabled(boolean)} — the kill switch for the O(1) indexed removal from the
     * engine's global entity array. Safe to flip live in either direction: re-enabling schedules a
     * one-off index rebuild on the main thread.
     */
    private static void applyEntityRemoveFastPath() {
        Boolean value = readBooleanOption(OPT_ENTITY_REMOVE_FAST_PATH);
        if (value == null) {
            return;
        }
        StormEntityIndex.setEnabled(value);
    }

    /**
     * Pushes {@link #OPT_VEHICLE_ALPHA_CHECK_SKIP} through {@link
     * StormVehicleAlphaCheckSkip#setEnabled(boolean)} — the kill switch for skipping the
     * server-dead {@code BaseVehicle.couldSeeIntersectedSquare} computation.
     */
    private static void applyVehicleAlphaCheckSkip() {
        Boolean value = readBooleanOption(OPT_VEHICLE_ALPHA_CHECK_SKIP);
        if (value == null) {
            return;
        }
        StormVehicleAlphaCheckSkip.setEnabled(value);
    }

    /**
     * Pushes {@link #OPT_VEHICLE_SOUND_RELEVANCE_FAST_PATH} through {@link
     * StormVehicleSoundRelevance#setEnabled(boolean)} — the kill switch for the per-tick hoist of
     * the vehicle-sound audible-radius predicates.
     */
    private static void applyVehicleSoundRelevanceFastPath() {
        Boolean value = readBooleanOption(OPT_VEHICLE_SOUND_RELEVANCE_FAST_PATH);
        if (value == null) {
            return;
        }
        StormVehicleSoundRelevance.setEnabled(value);
    }

    /**
     * Pushes {@link #OPT_ZOMBIE_SIGHT_VEHICLE_FAST_PATH} through {@link
     * ZombieVehicleOcclusion#setEnabled(boolean)} — the kill switch for the chunk-windowed
     * zombie-sight vehicle-occlusion fast path.
     */
    private static void applyZombieSightVehicleFastPath() {
        Boolean value = readBooleanOption(OPT_ZOMBIE_SIGHT_VEHICLE_FAST_PATH);
        if (value == null) {
            return;
        }
        ZombieVehicleOcclusion.setEnabled(value);
    }

    /**
     * Pushes {@link #OPT_PLAYER_LOS_FAST_PATH} through {@link StormPlayerLos#setEnabled(boolean)} —
     * the kill switch for the distance-culled, server-stripped {@code IsoPlayer.updateLOS()} fast
     * path.
     */
    private static void applyPlayerLosFastPath() {
        Boolean value = readBooleanOption(OPT_PLAYER_LOS_FAST_PATH);
        if (value == null) {
            return;
        }
        StormPlayerLos.setEnabled(value);
    }

    /**
     * Pushes {@link #OPT_USING_PLAYER_SWEEP_FAST_PATH} through {@link
     * UsingPlayerRegistry#setEnabled(boolean)} — the kill switch for the registry-backed {@code
     * UsingPlayerUpdateSystem.update()} sweep. Safe to flip live in either direction: registry
     * maintenance runs unconditionally, so the registry is complete even while the sweep is off.
     */
    private static void applyUsingPlayerSweepFastPath() {
        Boolean value = readBooleanOption(OPT_USING_PLAYER_SWEEP_FAST_PATH);
        if (value == null) {
            return;
        }
        UsingPlayerRegistry.setEnabled(value);
    }

    /**
     * Pushes {@link #OPT_FLUID_CONTAINER_UPDATE_FAST_PATH} through {@link
     * StormFluidContainerUpdate#setEnabled(boolean)} — the kill switch for the hoisted/reordered
     * {@code FluidContainerUpdateSystem.updateSimulation()} pass.
     */
    private static void applyFluidContainerUpdateFastPath() {
        Boolean value = readBooleanOption(OPT_FLUID_CONTAINER_UPDATE_FAST_PATH);
        if (value == null) {
            return;
        }
        StormFluidContainerUpdate.setEnabled(value);
    }

    /**
     * Pushes {@link #OPT_ECS_CLASS_CACHE} through {@link EcsClassCache#setEnabled(boolean)} — the
     * kill switch for the {@code ECSComponent.getECSClass(Class)} memoization. Always safe to flip
     * live in either direction: the cache is stateless toward the game.
     */
    private static void applyEcsClassCache() {
        Boolean value = readBooleanOption(OPT_ECS_CLASS_CACHE);
        if (value == null) {
            return;
        }
        EcsClassCache.setEnabled(value);
    }

    /**
     * Pushes {@link #OPT_REAP_STALLED_CONNECTION_SECONDS} through {@link
     * StalledConnectionReaper#setConnectTimeoutSecondsFromSandbox(int)}, which itself defers to
     * {@code -Dstorm.reapStalledConnectionMs} when that launch flag is set.
     */
    private static void applyReapStalledConnectionSeconds() {
        Integer value = readIntOption(OPT_REAP_STALLED_CONNECTION_SECONDS);
        if (value == null) {
            return;
        }
        StalledConnectionReaper.setConnectTimeoutSecondsFromSandbox(value);
    }

    /**
     * Pushes {@link #OPT_SERVER_FPS} through {@link ServerFpsConfig#applyUnifiedFps(int)}. Called
     * from {@link #applyAll()} (boot + admin push) and from {@link
     * io.pzstorm.storm.patch.networking.ServerLockFpsConfig#applyServerLockFps(int)} — the
     * substituted {@code PerformanceSettings.setLockFPS(10)} call at {@code GameServer.main()} line
     * 823, which runs immediately after {@link UpdateLimitFactory#create(long)} installs the tick
     * limiter at line 822.
     *
     * <p>{@code OnServerStartedEvent} fires inside {@code GameServer.startServer()} at line 1514,
     * before the patched {@code new UpdateLimit(100L)} at {@code GameServer.main()} line 822. The
     * first call from {@link #applyAll()} therefore arrives with no limiter installed; it returns
     * silently and waits for the {@code applyServerLockFps} boot seam to re-invoke this method. A
     * <em>second</em> not-ready call can only mean the {@code UpdateLimit} constructor substitution
     * never matched (a silent MemberSubstitution no-op), so it escalates to an error.
     */
    public static void applyServerFps() {
        Integer value = readIntOption(OPT_SERVER_FPS);
        if (value == null) {
            return;
        }
        if (!UpdateLimitFactory.isLimiterReady()) {
            if (serverFpsSkippedOnce) {
                LOGGER.error(
                        "Storm: Storm.ServerFps still cannot apply — the server tick limiter was"
                                + " never installed. GameServerTickRatePatch's UpdateLimit substitution"
                                + " likely did not match this game version; the server runs at vanilla"
                                + " 10 TPS");
            }
            serverFpsSkippedOnce = true;
            return;
        }
        ServerFpsConfig.applyUnifiedFps(value);
    }

    private static void applyAnimalLosTickInterval() {
        Integer value = readIntOption(OPT_ANIMAL_LOS_TICK_INTERVAL);
        if (value == null) {
            return;
        }
        AnimalLOSTickInterval.setTickInterval(value);
    }

    private static void applyVirtualAnimalTickInterval() {
        Integer value = readIntOption(OPT_VIRTUAL_ANIMAL_TICK_INTERVAL);
        if (value == null) {
            return;
        }
        VirtualAnimalTickInterval.setTickInterval(value);
    }

    private static void applyZombieAuthTickInterval() {
        Integer value = readIntOption(OPT_ZOMBIE_AUTH_TICK_INTERVAL);
        if (value == null) {
            return;
        }
        ZombieAuthTickInterval.setTickInterval(value);
    }

    private static void applyZombieRainWanderPercent() {
        Integer value = readIntOption(OPT_ZOMBIE_RAIN_WANDER_PERCENT);
        if (value == null) {
            return;
        }
        ZombieRainWanderInterval.setPercent(value);
    }

    /**
     * Pushes {@link #OPT_IMPORTANT_AREAS_MAXIMUM} through {@link
     * ImportantAreasPolicy#setMaximum(int)} — the cap on the engine's {@code ImportantAreaManager}
     * list, which vanilla inlines as 100. Read on every {@code updateOrAdd}, so live-appliable
     * trivially; a lowered cap trims one entry per miss rather than in one go.
     */
    private static void applyImportantAreasMaximum() {
        Integer value = readIntOption(OPT_IMPORTANT_AREAS_MAXIMUM);
        if (value == null) {
            return;
        }
        ImportantAreasPolicy.setMaximum(value);
    }

    private static void applyInventoryItemSweepTickInterval() {
        Integer value = readIntOption(OPT_INVENTORY_ITEM_SWEEP_TICK_INTERVAL);
        if (value == null) {
            return;
        }
        InventoryItemSweepTickInterval.setTickInterval(value);
    }

    /**
     * Zombie culling has no Storm option — since 42.20.0 operators drive vanilla's {@code
     * ZombieConfig.ZombiesCountBeforeDelete} directly. Only the gauge is republished so it tracks
     * admin pushes.
     */
    private static void refreshZombieCullThreshold() {
        StormZombieCullConfig.refreshMetric();
    }

    private static void applyMaxTotalZombies() {
        Integer value = readIntOption(OPT_MAX_TOTAL_ZOMBIES);
        if (value == null) {
            return;
        }
        StormZombieTotalCap.setMaxTotal(value);
    }

    private static void applyServerLosThreads() {
        Integer value = readIntOption(OPT_SERVER_LOS_THREADS);
        if (value == null) {
            return;
        }
        StormServerLosConfig.setThreads(value);
    }

    private static void applyNetDataCapMs() {
        Integer value = readIntOption(OPT_NETDATA_CAP_MS);
        if (value == null) {
            return;
        }
        MainLoopDrainCap.setCapMs(value);
    }

    private static void applyPeerSendBufferKickMb() {
        Integer value = readIntOption(OPT_PEER_SEND_BUFFER_KICK_MB);
        if (value == null) {
            return;
        }
        PeerSendBufferKickConfig.setKickMb(value);
    }

    private static void applyPeerSendBufferKickHoldTicks() {
        Integer value = readIntOption(OPT_PEER_SEND_BUFFER_KICK_HOLD_TICKS);
        if (value == null) {
            return;
        }
        PeerSendBufferKickConfig.setHoldTicks(value);
    }

    private static Boolean readBooleanOption(String name) {
        SandboxOptions.SandboxOption option;
        try {
            option = SandboxOptions.instance.getOptionByName(name);
        } catch (Exception e) {
            LOGGER.warn("Storm: sandbox option {} lookup failed", name, e);
            return null;
        }
        if (option == null) {
            LOGGER.warn("Storm: sandbox option {} not found; skipping", name);
            return null;
        }
        if (!(option instanceof SandboxOptions.BooleanSandboxOption booleanOption)) {
            LOGGER.warn("Storm: sandbox option {} is not a boolean option; skipping", name);
            return null;
        }
        return booleanOption.getValue();
    }

    private static Integer readIntOption(String name) {
        SandboxOptions.SandboxOption option;
        try {
            option = SandboxOptions.instance.getOptionByName(name);
        } catch (Exception e) {
            LOGGER.warn("Storm: sandbox option {} lookup failed", name, e);
            return null;
        }
        if (option == null) {
            LOGGER.warn("Storm: sandbox option {} not found; skipping", name);
            return null;
        }
        if (!(option instanceof SandboxOptions.IntegerSandboxOption integerOption)) {
            LOGGER.warn("Storm: sandbox option {} is not an integer option; skipping", name);
            return null;
        }
        return integerOption.getValue();
    }
}
