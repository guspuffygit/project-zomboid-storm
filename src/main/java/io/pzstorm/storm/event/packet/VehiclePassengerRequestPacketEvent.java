package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.vehicle.VehiclePassengerRequestPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.vehicle.VehiclePassengerRequestPacket}
 * is processed on the server.
 */
public class VehiclePassengerRequestPacketEvent extends PacketEvent {

    public VehiclePassengerRequestPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public VehiclePassengerRequestPacket getPacket() {
        return (VehiclePassengerRequestPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "VehiclePassengerRequestPacketEvent";
    }
}
