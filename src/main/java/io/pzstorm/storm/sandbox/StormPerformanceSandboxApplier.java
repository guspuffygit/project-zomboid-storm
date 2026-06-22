package io.pzstorm.storm.sandbox;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.OnServerStartedEvent;
import io.pzstorm.storm.event.zomboid.OnSandboxOptionsUpdateEvent;
import io.pzstorm.storm.los.StormServerLosConfig;
import io.pzstorm.storm.patch.networking.GameServerTickRatePatch.UpdateLimitFactory;
import io.pzstorm.storm.patch.networking.ServerFpsConfig;
import io.pzstorm.storm.patch.performance.AnimalLOSTickInterval;
import io.pzstorm.storm.patch.performance.StormZombieCullConfig;
import zombie.SandboxOptions;
import zombie.network.GameServer;

/**
 * Reads Storm's performance sandbox options at {@code OnServerStarted} and pushes them through the
 * existing live setters. {@code Storm.ServerFps} feeds {@link ServerFpsConfig#applyUnifiedFps(int)}
 * (which sets tick interval, lockFps, and IsoPhysicsObject fps); the remaining options ({@link
 * AnimalLOSTickInterval}, {@link StormZombieCullConfig}, {@link StormServerLosConfig}) are each 1:1
 * with a sandbox option.
 *
 * <p>This runs only on the dedicated server — the event also fires on the client when a hosted coop
 * server starts, but the sandbox knobs here only make sense for the authoritative server JVM.
 */
public final class StormPerformanceSandboxApplier {

    public static final String OPT_SERVER_FPS = "Storm.ServerFps";
    public static final String OPT_ANIMAL_LOS_TICK_INTERVAL = "Storm.AnimalLOSTickInterval";
    public static final String OPT_ZOMBIE_CULL_THRESHOLD = "Storm.ZombieCullThreshold";
    public static final String OPT_SERVER_LOS_THREADS = "Storm.ServerLosThreads";

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
        applyZombieCullThreshold();
        applyServerLosThreads();
    }

    /**
     * Pushes {@link #OPT_SERVER_FPS} through {@link ServerFpsConfig#applyUnifiedFps(int)}. Called
     * from {@link #applyAll()} (boot + admin push) and from {@link UpdateLimitFactory#create(long)}
     * once the server tick limiter is installed.
     *
     * <p>{@code OnServerStartedEvent} fires inside {@code GameServer.startServer()} at line 1513,
     * before the patched {@code new UpdateLimit(100L)} at {@code GameServer.main()} line 822. The
     * first call from {@link #applyAll()} therefore arrives with no limiter installed; it returns
     * silently and waits for {@code UpdateLimitFactory.create()} to re-invoke this method.
     */
    public static void applyServerFps() {
        Integer value = readIntOption(OPT_SERVER_FPS);
        if (value == null) {
            return;
        }
        if (!UpdateLimitFactory.isLimiterReady()) {
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

    private static void applyZombieCullThreshold() {
        Integer value = readIntOption(OPT_ZOMBIE_CULL_THRESHOLD);
        if (value == null) {
            return;
        }
        StormZombieCullConfig.setThreshold(value);
    }

    private static void applyServerLosThreads() {
        Integer value = readIntOption(OPT_SERVER_LOS_THREADS);
        if (value == null) {
            return;
        }
        StormServerLosConfig.setThreads(value);
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
