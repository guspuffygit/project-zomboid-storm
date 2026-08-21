package io.pzstorm.storm.vehicles;

import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import io.pzstorm.storm.metrics.VehicleSoundRelevanceMetrics;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import zombie.core.math.PZMath;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;

/**
 * Per-tick hoist of the connection-independent half of {@code
 * zombie.vehicleNetworkSound.server.Connection.isRelevant(BaseVehicle)}, wired in by {@code
 * VehicleSoundRelevancePatch}.
 *
 * <p>Vanilla {@code Manager.update()} walks every loaded vehicle once <em>per connection</em>
 * (~1,100 vehicles × ~70 connections per tick on a full server) and for each pair re-evaluates six
 * vehicle-only predicates (alarm, backup beeper, door alarm, engine state, horn, siren — the
 * audible radius) before the one connection-dependent test, {@code UdpConnection.RelevantTo(x, y,
 * radius)}. The predicates are plain reads of vehicle state and nothing inside {@code
 * Manager.update()} mutates a vehicle ({@code VehicleState.update} only reads and sends), so the
 * radius of a vehicle is the same for every connection of the same tick. Storm evaluates it once
 * per vehicle at {@code Manager.update()} entry into a snapshot of the noisy vehicles (radius &gt;
 * 0, {@code vehicleId != -1}, typically a few dozen) and answers every connection's {@code
 * getVehiclesRelevantToConnection} from that snapshot with vanilla's own {@code RelevantTo}. The
 * resulting set per connection is identical to vanilla's; only the work to produce it changes (~1.8
 * ms/tick → µs). Profiled at 4.1% of the main thread.
 *
 * <p>Fail-soft: any throwable latches the fast path off for the session and the vanilla body runs
 * (Byte Buddy skip semantics — the advice returns {@code false} and the original method executes
 * untouched). A missing snapshot (disabled, latched, or a caller other than the advised {@code
 * update()}) likewise falls through to vanilla.
 *
 * <p>Kill switch: the {@code Storm.VehicleSoundRelevanceFastPath} sandbox option.
 */
public final class StormVehicleSoundRelevance {

    public static final boolean DEFAULT_ENABLED = true;

    private static final String CONNECTION_CLASS = "zombie.vehicleNetworkSound.server.Connection";
    private static final String UDP_FIELD = "udpConnection";

    /** Kill switch; volatile because the sandbox applier may push updates from another thread. */
    private static volatile boolean enabled = DEFAULT_ENABLED;

    private static boolean failed;
    private static boolean snapshotValid;
    private static BaseVehicle[] noisy = new BaseVehicle[64];
    private static float[] radii = new float[64];
    private static int noisyCount;

    private static final MethodHandle UDP_GETTER = resolveUdpGetter();

    private StormVehicleSoundRelevance() {}

    private static MethodHandle resolveUdpGetter() {
        try {
            Field f = Class.forName(CONNECTION_CLASS).getDeclaredField(UDP_FIELD);
            f.setAccessible(true);
            return MethodHandles.lookup().unreflectGetter(f);
        } catch (Throwable t) {
            failed = true;
            VehicleSoundRelevanceMetrics.recordFailure();
            StormLogger.LOGGER.error(
                    "StormVehicleSoundRelevance: cannot access "
                            + CONNECTION_CLASS
                            + "."
                            + UDP_FIELD
                            + " — staying on the vanilla per-connection vehicle scan",
                    t);
            return null;
        }
    }

    /**
     * Applies the {@code Storm.VehicleSoundRelevanceFastPath} sandbox option and pushes the applied
     * value to the Prometheus gauge. Single mutation point — sandbox apply and tests both funnel
     * through here.
     *
     * @return the applied value
     */
    public static boolean setEnabled(boolean value) {
        enabled = value;
        StormPerformanceSandboxMetrics.setVehicleSoundRelevanceFastPath(value);
        return value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isFailed() {
        return failed;
    }

    /**
     * {@code Manager.update()} entry: snapshot every noisy vehicle's audible radius. Leaves the
     * snapshot invalid (→ vanilla per-connection scan) when disabled, latched, or on any throwable.
     */
    public static void beginTick() {
        snapshotValid = false;
        if (!enabled || failed) {
            return;
        }
        long start = System.nanoTime();
        try {
            noisyCount = 0;
            int scanned = 0;
            for (BaseVehicle vehicle : IsoWorld.instance.currentCell.getVehicles()) {
                scanned++;
                if (vehicle.vehicleId == -1) {
                    continue;
                }
                float radius = radiusFor(vehicle);
                if (radius > 0.0F) {
                    if (noisyCount == noisy.length) {
                        noisy = Arrays.copyOf(noisy, noisyCount * 2);
                        radii = Arrays.copyOf(radii, noisyCount * 2);
                    }
                    noisy[noisyCount] = vehicle;
                    radii[noisyCount] = radius;
                    noisyCount++;
                }
            }
            snapshotValid = true;
            VehicleSoundRelevanceMetrics.recordSnapshot(
                    scanned, noisyCount, System.nanoTime() - start);
        } catch (Throwable t) {
            latch("snapshot", t);
        }
    }

    /** {@code Manager.update()} exit (normal or exceptional): drop the snapshot. */
    public static void endTick() {
        snapshotValid = false;
        Arrays.fill(noisy, 0, noisyCount, null);
        noisyCount = 0;
    }

    /**
     * Replacement for {@code Manager.getVehiclesRelevantToConnection(Connection, Set)}.
     *
     * @param connection the package-private {@code Connection} (read for its {@code
     *     udpConnection}); {@code null} falls through to vanilla so its behaviour is preserved
     * @param vehicles the manager's scratch set, cleared and filled exactly as vanilla would
     * @return {@code true} when {@code vehicles} was filled here and the vanilla body must be
     *     skipped; {@code false} to run the vanilla body
     */
    public static boolean fill(Object connection, Set<BaseVehicle> vehicles) {
        if (!snapshotValid || connection == null) {
            VehicleSoundRelevanceMetrics.connectionsVanilla++;
            return false;
        }
        try {
            UdpConnection udp = (UdpConnection) UDP_GETTER.invoke(connection);
            if (udp == null) {
                VehicleSoundRelevanceMetrics.connectionsVanilla++;
                return false;
            }
            vehicles.clear();
            for (int i = 0; i < noisyCount; i++) {
                BaseVehicle vehicle = noisy[i];
                if (udp.RelevantTo(vehicle.getX(), vehicle.getY(), radii[i])) {
                    vehicles.add(vehicle);
                }
            }
            VehicleSoundRelevanceMetrics.connectionsFast++;
            return true;
        } catch (Throwable t) {
            latch("fill", t);
            VehicleSoundRelevanceMetrics.connectionsVanilla++;
            return false;
        }
    }

    /**
     * Vanilla {@code Connection.isRelevant}'s audible radius, predicates in vanilla order. Every
     * predicate is evaluated (none is skipped on an earlier 500) so call counts stay vanilla-shaped
     * per evaluation.
     */
    public static float radiusFor(BaseVehicle vehicle) {
        return radiusFor(
                vehicle.isAlarmActive(),
                vehicle.isBackupBeeperSounding(),
                vehicle.isDoorAlarmSounding(),
                vehicle.getEngineState() != BaseVehicle.engineStateTypes.Idle,
                vehicle.isHornSounding(),
                vehicle.isSirenSounding());
    }

    /** Pure form of {@link #radiusFor(BaseVehicle)}; the numbers are vanilla's. */
    public static float radiusFor(
            boolean alarmActive,
            boolean backupBeeper,
            boolean doorAlarm,
            boolean engineNotIdle,
            boolean horn,
            boolean siren) {
        float radius = 0.0F;
        if (alarmActive) {
            radius = PZMath.max(radius, 500.0F);
        }
        if (backupBeeper) {
            radius = PZMath.max(radius, 150.0F);
        }
        if (doorAlarm) {
            radius = PZMath.max(radius, 50.0F);
        }
        if (engineNotIdle) {
            radius = PZMath.max(radius, 200.0F);
        }
        if (horn) {
            radius = PZMath.max(radius, 500.0F);
        }
        if (siren) {
            radius = PZMath.max(radius, 500.0F);
        }
        return radius;
    }

    private static void latch(String stage, Throwable t) {
        failed = true;
        snapshotValid = false;
        VehicleSoundRelevanceMetrics.recordFailure();
        StormLogger.LOGGER.error(
                "StormVehicleSoundRelevance "
                        + stage
                        + " failed — reverting to the vanilla per-connection vehicle scan",
                t);
    }
}
