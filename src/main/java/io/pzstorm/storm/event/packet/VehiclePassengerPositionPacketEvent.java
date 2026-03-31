package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.vehicle.VehiclePassengerPositionPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.vehicle.VehiclePassengerPositionPacket}
 * is processed on the server.
 */
public class VehiclePassengerPositionPacketEvent extends PacketEvent {

    public VehiclePassengerPositionPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public VehiclePassengerPositionPacket getPacket() {
        return (VehiclePassengerPositionPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "VehiclePassengerPositionPacketEvent";
    }
}
