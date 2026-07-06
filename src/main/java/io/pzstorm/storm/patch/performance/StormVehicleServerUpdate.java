package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.MainLoopStepTimings;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import zombie.MovingObjectUpdateScheduler;
import zombie.core.raknet.UdpConnection;
import zombie.core.utils.UpdateLimit;
import zombie.debug.DebugType;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleManager;
import zombie.vehicles.VehiclePart;

/**
 * Optimized replacement for {@code VehicleManager.serverUpdate()}.
 *
 * <p>Vanilla wastes two ways (see {@code zombie/vehicles/VehicleManager.java:104-149}):
 *
 * <ul>
 *   <li>The outer loop visits all 512 {@code connected} slots; for every <em>null</em> slot the
 *       else-branch still iterates the entire vehicle set checking {@code connectionState[i]} —
 *       provably always null there, because {@code connectionState[i]} is only assigned in {@code
 *       sendVehicles} immediately before {@code connected[i]} is set, and both are cleared together
 *       on drop. With V vehicles that is ~512×V no-op set iterations per call.
 *   <li>It runs twice per frame: {@code GameServer.main} calls it right after {@code
 *       statex.update()}, and {@code IsoWorld.update()} (inside {@code statex.update()}) calls it
 *       too. {@code updateFlags} are sticky until the rate-gated send block clears them, so
 *       collapsing to once per frame only delays flag pickup by at most one frame.
 * </ul>
 *
 * <p>This helper runs the same logic but only for non-null slots, and only once per frame — deduped
 * on {@link MovingObjectUpdateScheduler#getFrameCounter()}, which increments between vanilla's two
 * call sites, so the surviving execution is the later (main-loop) one and flag pickup latency is
 * unchanged. The rate-gated send block is kept verbatim, reaching the private {@code
 * updateRate}/{@code updatePassengers}/{@code sendVehicles} members reflectively (cached; the send
 * block runs at most 10×/s).
 *
 * <p>Kill switch: {@code -Dstorm.vehicleupdate.optimize=false} restores vanilla behavior.
 */
public final class StormVehicleServerUpdate {

    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("storm.vehicleupdate.optimize"));

    private static long lastRunFrame = Long.MIN_VALUE;

    private static Field updateRateField;
    private static Field updatePassengersField;
    private static Field partUpdateFlagsField;
    private static Method sendVehiclesMethod;
    private static boolean reflectionFailed;

    private StormVehicleServerUpdate() {}

    /**
     * Called by {@code VehicleManagerServerUpdateOptAdvice.onEnter}. Returns {@code true} when the
     * vanilla body must be skipped (either the optimized version ran, or this is the redundant
     * second call this frame); {@code false} falls through to vanilla.
     */
    public static boolean runOptimizedOrSkip() {
        if (!ENABLED || !GameServer.server || reflectionFailed) {
            return false;
        }
        VehicleManager vm = VehicleManager.instance;
        if (vm == null || IsoWorld.instance == null || IsoWorld.instance.currentCell == null) {
            return false;
        }
        MovingObjectUpdateScheduler scheduler = MovingObjectUpdateScheduler.instance;
        if (scheduler == null) {
            return false;
        }
        long frame = scheduler.getFrameCounter();
        if (frame == lastRunFrame) {
            // Vanilla calls serverUpdate twice per frame (IsoWorld.update, then GameServer.main)
            // and the frame counter increments between them (IsoCell.update -> startFrame). In
            // steady state the main-loop call runs and stamps frame N; the next frame's IsoWorld
            // call still sees N and lands here — exactly one execution per frame, at the later
            // call site, which sees updateFlags set earlier in the same frame.
            return true;
        }
        if (!ensureReflection()) {
            return false;
        }
        long start = System.nanoTime();
        try {
            runOptimized(vm);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("StormVehicleServerUpdate reflection failed — reverting to vanilla", e);
            reflectionFailed = true;
            return false;
        }
        // Mark the frame only after success so a throw retries via the vanilla body next call.
        lastRunFrame = frame;
        MainLoopStepTimings.record("VehicleManager.serverUpdate", System.nanoTime() - start);
        return true;
    }

    /** Vanilla {@code serverUpdate} body minus the null-slot vehicle scans. */
    private static void runOptimized(VehicleManager vm) throws ReflectiveOperationException {
        Set<BaseVehicle> vehicles = IsoWorld.instance.currentCell.getVehicles();

        for (int i = 0; i < vm.connected.length; i++) {
            UdpConnection connection = vm.connected[i];
            if (connection == null) {
                // Invariant: connectionState[i] is only set in sendVehicles immediately before
                // connected[i] is assigned, and both are cleared together on drop — so a null
                // slot can have no per-vehicle state and the vanilla scan is a no-op.
                continue;
            }
            if (!GameServer.udpEngine.connections.contains(connection)) {
                DebugType.Vehicle.trace("vehicles: dropped connection %d", i);
                for (BaseVehicle vehicle : vehicles) {
                    vehicle.connectionState[i] = null;
                }
                vm.connected[i] = null;
            } else {
                for (BaseVehicle vehicle : vehicles) {
                    BaseVehicle.ServerVehicleState state = vehicle.connectionState[i];
                    if (state != null) {
                        state.flags = (short) (state.flags | vehicle.updateFlags);
                    }
                }
            }
        }

        UpdateLimit updateRate = (UpdateLimit) updateRateField.get(vm);
        if (updateRate.Check()) {
            UpdateLimit updatePassengers = (UpdateLimit) updatePassengersField.get(vm);
            if (updatePassengers.Check()) {
                for (BaseVehicle vehicle : vehicles) {
                    vehicle.updateFlags = (short) (vehicle.updateFlags | 16384);
                }
            }

            for (int j = 0; j < GameServer.udpEngine.connections.size(); j++) {
                UdpConnection connection = GameServer.udpEngine.connections.get(j);
                sendVehiclesMethod.invoke(vm, connection);
                vm.connected[connection.getIndex()] = connection;
            }

            for (BaseVehicle vehicle : vehicles) {
                if ((vehicle.updateFlags & 3056) != 0) {
                    for (int j = 0; j < vehicle.getPartCount(); j++) {
                        VehiclePart part = vehicle.getPartByIndex(j);
                        // VehiclePart.updateFlags is protected; this branch only runs inside
                        // the 100ms-gated send block for vehicles with part flags set.
                        partUpdateFlagsField.setShort(part, (short) 0);
                    }
                }
                vehicle.updateFlags = 0;
            }
        }
    }

    private static boolean ensureReflection() {
        if (sendVehiclesMethod != null) {
            return true;
        }
        try {
            updateRateField = VehicleManager.class.getDeclaredField("updateRate");
            updateRateField.setAccessible(true);
            updatePassengersField = VehicleManager.class.getDeclaredField("updatePassengers");
            updatePassengersField.setAccessible(true);
            partUpdateFlagsField = VehiclePart.class.getDeclaredField("updateFlags");
            partUpdateFlagsField.setAccessible(true);
            Method m = VehicleManager.class.getDeclaredMethod("sendVehicles", UdpConnection.class);
            m.setAccessible(true);
            sendVehiclesMethod = m;
            return true;
        } catch (ReflectiveOperationException | SecurityException e) {
            LOGGER.error(
                    "StormVehicleServerUpdate cannot access VehicleManager members —"
                            + " reverting to vanilla serverUpdate",
                    e);
            reflectionFailed = true;
            return false;
        }
    }

    /** Test-only — resets the per-frame dedupe latch. */
    static void resetForTest() {
        lastRunFrame = Long.MIN_VALUE;
    }
}
