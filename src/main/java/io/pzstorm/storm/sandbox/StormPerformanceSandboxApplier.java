package io.pzstorm.storm.sandbox;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.advice.netdatadraincap.MainLoopDrainCap;
import io.pzstorm.storm.connection.PeerSendBufferKickConfig;
import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.OnServerStartedEvent;
import io.pzstorm.storm.event.zomboid.OnSandboxOptionsUpdateEvent;
import io.pzstorm.storm.los.StormServerLosConfig;
import io.pzstorm.storm.patch.networking.GameServerTickRatePatch.UpdateLimitFactory;
import io.pzstorm.storm.patch.networking.ServerFpsConfig;
import io.pzstorm.storm.patch.performance.AnimalLOSTickInterval;
import io.pzstorm.storm.patch.performance.InventoryItemSweepTickInterval;
import io.pzstorm.storm.patch.performance.StormZombieCullConfig;
import io.pzstorm.storm.patch.performance.VirtualAnimalTickInterval;
import io.pzstorm.storm.patch.performance.ZombieAuthTickInterval;
import io.pzstorm.storm.screenshot.StormScreenshotConfig;
import io.pzstorm.storm.zombie.StormZombieTotalCap;
import zombie.SandboxOptions;
import zombie.network.GameServer;

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
    public static final String OPT_INVENTORY_ITEM_SWEEP_TICK_INTERVAL =
            "Storm.InventoryItemSweepTickInterval";
    public static final String OPT_MAX_TOTAL_ZOMBIES = "Storm.MaxTotalZombies";
    public static final String OPT_SERVER_LOS_THREADS = "Storm.ServerLosThreads";
    public static final String OPT_NETDATA_CAP_MS = "Storm.NetDataCapMs";
    public static final String OPT_PEER_SEND_BUFFER_KICK_MB = "Storm.PeerSendBufferKickMb";
    public static final String OPT_PEER_SEND_BUFFER_KICK_HOLD_TICKS =
            "Storm.PeerSendBufferKickHoldTicks";
    public static final String OPT_SCREENSHOT_PIECES_PER_PACKET = "Storm.ScreenshotPiecesPerPacket";
    public static final String OPT_SCREENSHOT_UPLOAD_KB_PER_SEC = "Storm.ScreenshotUploadKbPerSec";
    public static final String OPT_SCREENSHOT_ENCODE_KB_PER_TICK =
            "Storm.ScreenshotEncodeKbPerTick";

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
        applyInventoryItemSweepTickInterval();
        refreshZombieCullThreshold();
        applyMaxTotalZombies();
        applyServerLosThreads();
        applyNetDataCapMs();
        applyPeerSendBufferKickMb();
        applyPeerSendBufferKickHoldTicks();
        applyScreenshotPiecesPerPacket();
        applyScreenshotUploadKbPerSec();
        applyScreenshotEncodeKbPerTick();
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

    private static void applyScreenshotPiecesPerPacket() {
        Integer value = readIntOption(OPT_SCREENSHOT_PIECES_PER_PACKET);
        if (value == null) {
            return;
        }
        StormScreenshotConfig.setPiecesPerPacket(value);
    }

    private static void applyScreenshotUploadKbPerSec() {
        Integer value = readIntOption(OPT_SCREENSHOT_UPLOAD_KB_PER_SEC);
        if (value == null) {
            return;
        }
        StormScreenshotConfig.setUploadKbPerSec(value);
    }

    private static void applyScreenshotEncodeKbPerTick() {
        Integer value = readIntOption(OPT_SCREENSHOT_ENCODE_KB_PER_TICK);
        if (value == null) {
            return;
        }
        StormScreenshotConfig.setEncodeKbPerTick(value);
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
