package io.pzstorm.storm.advice.client.vehiclesounds;

import io.pzstorm.storm.logging.StormLogger;
import zombie.network.GameClient;
import zombie.vehicleSound.VehicleSounds;
import zombie.vehicles.BaseVehicle;

/**
 * Creates the missing {@link VehicleSounds} on multiplayer clients so the client branch of {@code
 * BaseVehicle.onEngineStateChanged} cannot NPE.
 *
 * <p>Vanilla {@code checkVehicleSoundsExists()} is gated to single player ({@code
 * !GameClient.client && !GameServer.server}), yet the client branch of {@code onEngineStateChanged}
 * calls it and then unconditionally dereferences {@code getVehicleSounds()}. On MP clients the
 * sounds object is normally attached later by the vehicle-network-sound {@code VehicleState}, but
 * only once the vehicle is present in {@code cell.vehicles} — an engine-state packet arriving in
 * that gap throws inside {@code VehicleEngine.parse}, which aborts {@code
 * VehicleUpdatePacket.parse} mid-body and silently discards every field after the engine section,
 * including the authoritative passenger/seat list (observed as {@code Unexpected buffer position.
 * Read bytes 1, expected: 13}).
 *
 * <p>Pre-creating the instance is safe: {@code BaseVehicle.setVehicleSounds} already handles
 * replacing an existing instance (it removes the old one), which is exactly what the network sound
 * {@code VehicleState.update()} does on its next handover.
 *
 * <p>Fail-soft: any error permanently disables the helper and vanilla behavior resumes.
 */
public final class VehicleSoundsClientCreate {

    public static volatile boolean disabled;

    private VehicleSoundsClientCreate() {}

    public static void ensureExists(BaseVehicle vehicle) {
        if (disabled || !GameClient.client || vehicle == null) {
            return;
        }
        try {
            if (vehicle.getVehicleSounds() == null) {
                vehicle.setVehicleSounds(new VehicleSounds());
            }
        } catch (Throwable t) {
            disabled = true;
            StormLogger.LOGGER.error("VehicleSoundsClientCreate disabled after error", t);
        }
    }
}
