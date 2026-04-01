package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.fields.vehicle.VehicleID;
import zombie.network.packets.hit.PlayerHitVehiclePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.hit.PlayerHitVehiclePacket} is processed on
 * the server.
 */
public class PlayerHitVehiclePacketEvent extends PacketEvent {

    public PlayerHitVehiclePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public PlayerHitVehiclePacket getPacket() {
        return (PlayerHitVehiclePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "PlayerHitVehiclePacketEvent";
    }

    public VehicleID getVehicleId() {
        return (VehicleID) getField("vehicleId");
    }

    public Float getDamage() {
        return (Float) getField("damage");
    }
}
