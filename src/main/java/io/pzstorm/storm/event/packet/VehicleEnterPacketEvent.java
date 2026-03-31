package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.vehicle.VehicleEnterPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.vehicle.VehicleEnterPacket} is
 * processed on the server.
 */
public class VehicleEnterPacketEvent extends PacketEvent {

    public VehicleEnterPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public VehicleEnterPacket getPacket() {
        return (VehicleEnterPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "VehicleEnterPacketEvent";
    }
}
