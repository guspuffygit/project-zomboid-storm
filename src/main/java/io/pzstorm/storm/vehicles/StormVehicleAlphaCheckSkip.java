package io.pzstorm.storm.vehicles;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import io.pzstorm.storm.metrics.VehicleAlphaCheckSkipMetrics;
import zombie.network.GameServer;

/**
 * Server-side short-circuit for {@code BaseVehicle.couldSeeIntersectedSquare(int)}, wired in by
 * {@code BaseVehicleAlphaCheckSkipPatch}.
 *
 * <p>Vanilla {@code BaseVehicle.update()} calls it once per vehicle per tick (per local player
 * index — one on a dedicated server) purely to decide whether to call {@code setTargetAlpha(index,
 * 0)}, the client fade-out for vehicles the player cannot see. {@code IsoObject.setTargetAlpha} is
 * a no-op when {@code GameServer.server} is set, {@code getTargetAlpha} returns a constant {@code
 * 1.0F} there, and the only other consumer of alpha ({@code MovingObjectUpdateScheduler}'s
 * simulation-level throttling) is also client-only. The method itself is read-only: {@code
 * getPoly()} lazily refreshes the vehicle polygon (every later reader refreshes it just the same),
 * and the rest walks the poly's bounding squares through {@code IsoGridSquare.isCouldSee} and a
 * polygon intersection. So on the server its result is never observed and its side effects are nil;
 * skipping it is exact. Profiled at ~4.7% of the main thread with ~1,100 vehicles.
 *
 * <p>The skip is gated on {@code GameServer.server} at call time (never fires in a hosted co-op
 * client JVM, where the host is also a rendering player) and on the {@code
 * Storm.VehicleAlphaCheckSkip} sandbox option.
 */
public final class StormVehicleAlphaCheckSkip {

    public static final boolean DEFAULT_ENABLED = true;

    /** Kill switch; volatile because the sandbox applier may push updates from another thread. */
    private static volatile boolean enabled = DEFAULT_ENABLED;

    /** Skipped calls. Main-thread writer only; read by the metrics callback. */
    public static long skips;

    static {
        VehicleAlphaCheckSkipMetrics.init();
    }

    private StormVehicleAlphaCheckSkip() {}

    /**
     * Applies the {@code Storm.VehicleAlphaCheckSkip} sandbox option and pushes the applied value
     * to the Prometheus gauge. Single mutation point — sandbox apply and tests both funnel through
     * here.
     *
     * @return the applied value
     */
    public static boolean setEnabled(boolean value) {
        enabled = value;
        StormPerformanceSandboxMetrics.setVehicleAlphaCheckSkip(value);
        return value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Advice entry: {@code true} skips the vanilla body (the advised method then returns {@code
     * false}, whose only consumer is the server no-op {@code setTargetAlpha(index, 0)}).
     */
    public static boolean shouldSkip() {
        if (!enabled || !GameServer.server) {
            return false;
        }
        skips++;
        return true;
    }
}
