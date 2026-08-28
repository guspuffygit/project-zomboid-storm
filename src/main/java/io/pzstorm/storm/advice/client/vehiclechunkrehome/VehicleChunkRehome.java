package io.pzstorm.storm.advice.client.vehiclechunkrehome;

import io.pzstorm.storm.logging.StormLogger;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * Re-homes a vehicle whose home chunk was unloaded while a local player is aboard, before vanilla
 * {@code BaseVehicle.update()} freezes it.
 *
 * <p>When a chunk scrolls out of the client's loaded area, {@code IsoChunk.removeFromWorld} tries
 * to remove the vehicles filed under it. {@code BaseVehicle.removeFromWorld()} refuses when a local
 * player occupies a seat — but the same unload already nulled the vehicle's {@code current} square,
 * and the chunk unconditionally leaves the chunk map. From then on every {@code update()} call
 * takes the {@code chunk.refs.isEmpty()} branch: it calls {@code removeFromWorld()} (which no-ops
 * again) and returns before {@code super.update()}, so the vehicle stops moving, interpolating and
 * simulating on this client forever while the server keeps driving it. Vanilla's own re-home block
 * later in {@code update()} is unreachable because it requires a non-null {@code current}.
 *
 * <p>This helper detects that state on update entry and re-files the vehicle under the live chunk
 * at its actual position (which is loaded in practice — the chunk map is centered on the aboard
 * player), restoring {@code current} first so vanilla's own bookkeeping works again. If the square
 * has not streamed in yet it leaves the vehicle alone and retries next tick, which is still
 * strictly better than vanilla's permanent freeze.
 *
 * <p>Fail-soft: any error permanently disables the helper and vanilla behavior resumes.
 */
public final class VehicleChunkRehome {

    public static volatile boolean disabled;

    private VehicleChunkRehome() {}

    public static void beforeUpdate(BaseVehicle vehicle) {
        if (disabled || GameServer.server || vehicle == null) {
            return;
        }
        try {
            if (vehicle.isRemovedFromWorld()) {
                return;
            }
            IsoChunk home = vehicle.chunk;
            if (home == null || !home.refs.isEmpty()) {
                return;
            }
            if (!hasLocalPlayerAboard(vehicle)) {
                return;
            }
            vehicle.setCurrentSquareFromPosition();
            IsoGridSquare square = vehicle.getCurrentSquare();
            if (square == null) {
                return;
            }
            IsoChunk fresh = square.getChunk();
            if (fresh == null || fresh == home || fresh.refs.isEmpty()) {
                return;
            }
            home.vehicles.remove(vehicle);
            if (!fresh.vehicles.contains(vehicle)) {
                fresh.vehicles.add(vehicle);
            }
            vehicle.chunk = fresh;
            IsoChunk.addFromCheckedVehicles(vehicle);
            StormLogger.LOGGER.info(
                    "Chunk re-home: vehicle id={} ({}) with local player aboard re-homed from"
                            + " unloaded chunk {},{} to {},{} at {},{},{}",
                    vehicle.getId(),
                    vehicle.getScriptName(),
                    home.wx,
                    home.wy,
                    fresh.wx,
                    fresh.wy,
                    vehicle.getX(),
                    vehicle.getY(),
                    vehicle.getZ());
        } catch (Throwable t) {
            disabled = true;
            StormLogger.LOGGER.error("VehicleChunkRehome disabled after error", t);
        }
    }

    private static boolean hasLocalPlayerAboard(BaseVehicle vehicle) {
        int seats = vehicle.getMaxPassengers();
        for (int seat = 0; seat < seats; seat++) {
            BaseVehicle.Passenger passenger = vehicle.getPassenger(seat);
            if (passenger == null || passenger.character == null) {
                continue;
            }
            for (int i = 0; i < IsoPlayer.players.length; i++) {
                if (passenger.character == IsoPlayer.players[i]) {
                    return true;
                }
            }
        }
        return false;
    }
}
