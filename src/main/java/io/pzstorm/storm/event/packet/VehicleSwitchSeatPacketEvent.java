package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.vehicle.VehicleSwitchSeatPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.vehicle.VehicleSwitchSeatPacket} is
 * processed on the server.
 */
public class VehicleSwitchSeatPacketEvent extends PacketEvent {

    public VehicleSwitchSeatPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public VehicleSwitchSeatPacket getPacket() {
        return (VehicleSwitchSeatPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "VehicleSwitchSeatPacketEvent";
    }
}
