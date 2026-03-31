package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.vehicle.VehicleExitPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.vehicle.VehicleExitPacket} is processed
 * on the server.
 */
public class VehicleExitPacketEvent extends PacketEvent {

    public VehicleExitPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public VehicleExitPacket getPacket() {
        return (VehicleExitPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "VehicleExitPacketEvent";
    }
}
