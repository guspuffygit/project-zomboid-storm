package io.pzstorm.storm.vehicles;

import io.pzstorm.storm.metrics.VehicleBreakingObjectsSkipMetrics;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * Server-side skip for {@code BaseVehicle.breakingObjects()} on vehicles without a driver, wired in
 * by {@code BaseVehicleBreakingObjectsSkipPatch}.
 *
 * <p>Vanilla {@code BaseVehicle.update()} calls {@code breakingObjects()} every tick for every
 * vehicle whose engine is running (or that is towed by one): a ~63-square walk around the vehicle
 * probing every object on every square for {@code CarSlowFactor}/{@code HitByCar} properties, plus
 * per-square moving-object registration. Profiled at ~1.9 ms/tick on ATF (scan #5, 1,792 vehicles)
 * — mostly engine-running vehicles sitting still.
 *
 * <p>This is a deliberate behavior change, not an exact skip (decided 2026-08-28): on the dedicated
 * server, only <b>driven</b> vehicles interact with breakable/slowing world objects. A driverless
 * vehicle — parked with the engine running, or shoved by a collision — no longer breaks tiles,
 * plows plants, crushes corpses, or registers itself on nearby zombies/players for run-over checks
 * via this path. Towed vehicles follow their tow chain: a trailer dragged by a driven vehicle still
 * scans.
 *
 * <p>Stale state is self-healing: {@code breakingObjectsList}/{@code breakingSlowFactor} freeze
 * while skipped, and the first driven tick rescans both (the scan itself prunes entries that no
 * longer collide). {@code breakingSlowFactor} is only consumed by the velocity cap, which is
 * irrelevant while nobody is driving.
 *
 * <p>Gated on {@code GameServer.server} at call time (never fires in single-player or a hosted
 * co-op client JVM). Always on — no sandbox option.
 */
public final class StormVehicleBreakingObjectsSkip {

    /** Longest tow chain walked before giving up and treating the vehicle as driverless. */
    private static final int MAX_TOW_HOPS = 4;

    /** Skipped calls. Main-thread writer only; read by the metrics callback. */
    public static long skips;

    static {
        VehicleBreakingObjectsSkipMetrics.init();
    }

    private StormVehicleBreakingObjectsSkip() {}

    /** Advice entry: {@code true} skips the vanilla body. */
    public static boolean shouldSkip(Object vehicleObj) {
        if (!GameServer.server) {
            return false;
        }
        BaseVehicle vehicle = (BaseVehicle) vehicleObj;
        BaseVehicle head = vehicle;
        for (int hops = 0; hops <= MAX_TOW_HOPS; hops++) {
            if (head.getDriver() != null) {
                return false;
            }
            BaseVehicle towedBy = head.getVehicleTowedBy();
            if (towedBy == null || towedBy == head) {
                break;
            }
            head = towedBy;
        }
        skips++;
        return true;
    }
}
