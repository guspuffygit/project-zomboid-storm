package io.pzstorm.storm.vehicles;

import io.pzstorm.storm.metrics.BaseVehicleUpdateMetrics;
import io.pzstorm.storm.metrics.VehicleSleepMetrics;
import java.util.ArrayList;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * Server-side update throttle for inert vehicles, wired into {@code BaseVehicle.update()} by {@code
 * BaseVehicleUpdatePatch}.
 *
 * <p>On a populated server the overwhelming majority of loaded vehicles (87% measured live at 1,774
 * vehicles) are parked: no driver, no passengers, engine off, physics deactivated, nothing drawing
 * power. Vanilla still runs the full {@code update()} for each of them every tick &mdash; a
 * ~30-part device scan, the engine state machine, {@code isAtRest()}, sound and light bookkeeping
 * &mdash; ~7&nbsp;ms/tick total. A vehicle that passes the cheap guard below runs its full update
 * only once every {@link #SLEEP_TICKS} ticks, staggered by {@code vehicleId}.
 *
 * <p>Why skipping ticks is exact and not an approximation:
 *
 * <ul>
 *   <li>Part simulation is time-integrated, not per-tick. {@code VehicleParts.updatePart} computes
 *       elapsed in-game minutes since the part's {@code lastUpdated} stamp and no-ops under one
 *       minute; the next full update integrates the whole skipped interval, so battery drain,
 *       condition wear and fuel come out identical.
 *   <li>Every player-visible wake reason flips a field this guard reads every tick &mdash; enter
 *       vehicle, start engine, hook a tow, open the mechanics window, alarm, headlights, physics
 *       (re)activation by an impulse or network authority &mdash; so the vehicle resumes full
 *       updates on the very next tick, not after the throttle period.
 *   <li>State only reachable through the skipped body (pending tow-reconnect by id, lightbar
 *       world-light transitions, the {@code addThumpWorldSound} flag) is picked up on the next
 *       staggered full tick, at most {@code SLEEP_TICKS - 1} ticks late.
 * </ul>
 *
 * <p>{@code timeSinceLastAuth > 0} blocks sleep and cannot deadlock: while it is positive the
 * vehicle takes full updates, and the full update is what decrements it.
 *
 * <p>Configured with {@code -Dstorm.vehicle.sleepTicks=N} (default {@value #DEFAULT_SLEEP_TICKS};
 * {@code 0} or {@code 1} disables the throttle entirely).
 */
public final class StormVehicleSleep {

    public static final int DEFAULT_SLEEP_TICKS = 10;

    /** Full-update period in ticks for sleeping vehicles; &le;1 disables the throttle. */
    public static final int SLEEP_TICKS =
            Math.max(0, Integer.getInteger("storm.vehicle.sleepTicks", DEFAULT_SLEEP_TICKS));

    /** Server tick counter driving the stagger; incremented by {@code ServerTickAdvice}. */
    public static long tick;

    /** Skipped {@code BaseVehicle.update()} calls. Main-thread writer; read at scrape time. */
    public static long skips;

    /** Executed {@code BaseVehicle.update()} calls. Main-thread writer; read at scrape time. */
    public static long fullUpdates;

    static {
        VehicleSleepMetrics.init();
    }

    private StormVehicleSleep() {}

    public static void onServerTick() {
        tick++;
    }

    /**
     * Advice entry for {@code BaseVehicle.update()}. Returns {@code 0} to skip the vanilla body
     * (the vehicle is asleep this tick), {@code -1} to run it untimed, or a {@code nanoTime}
     * timestamp to run it and record a sampled duration on exit.
     */
    public static long enterUpdate(Object vehicleObj) {
        if (!GameServer.server) {
            return -1L;
        }
        if (shouldSkip(vehicleObj)) {
            skips++;
            return 0L;
        }
        fullUpdates++;
        return BaseVehicleUpdateMetrics.shouldSample() ? System.nanoTime() : -1L;
    }

    private static boolean shouldSkip(Object vehicleObj) {
        if (SLEEP_TICKS <= 1) {
            return false;
        }
        BaseVehicle v = (BaseVehicle) vehicleObj;
        if ((tick + (v.vehicleId & 0xFFFF)) % SLEEP_TICKS == 0) {
            return false;
        }
        if (v.getDriver() != null || v.isPhysicsActive() || v.needPartsUpdate()) {
            return false;
        }
        if (v.headlightsOn || v.jniIsCollide || v.timeSinceLastAuth > 0.0F) {
            return false;
        }
        if (v.getEngineState() != BaseVehicle.engineStateTypes.Idle) {
            return false;
        }
        if (v.getVehicleTowing() != null || v.getVehicleTowedBy() != null) {
            return false;
        }
        if (v.isAlarmed() || v.isSirenActive() || v.isMechanicUIOpen()) {
            return false;
        }
        if (v.getSquare() == null) {
            return false;
        }
        for (int i = 0, max = v.getMaxPassengers(); i < max; i++) {
            BaseVehicle.Passenger passenger = v.getPassenger(i);
            if (passenger != null && passenger.character != null) {
                return false;
            }
        }
        ArrayList<?> animals = v.getAnimals();
        return animals == null || animals.isEmpty();
    }
}
