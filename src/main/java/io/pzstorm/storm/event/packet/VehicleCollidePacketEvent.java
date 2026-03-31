package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.vehicle.VehicleCollidePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.vehicle.VehicleCollidePacket} is
 * processed on the server.
 */
public class VehicleCollidePacketEvent extends PacketEvent {

    public VehicleCollidePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public VehicleCollidePacket getPacket() {
        return (VehicleCollidePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "VehicleCollidePacketEvent";
    }
}
