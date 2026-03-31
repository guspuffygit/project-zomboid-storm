package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.vehicle.VehicleRequestPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.vehicle.VehicleRequestPacket} is
 * processed on the server.
 */
public class VehicleRequestPacketEvent extends PacketEvent {

    public VehicleRequestPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public VehicleRequestPacket getPacket() {
        return (VehicleRequestPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "VehicleRequestPacketEvent";
    }
}
