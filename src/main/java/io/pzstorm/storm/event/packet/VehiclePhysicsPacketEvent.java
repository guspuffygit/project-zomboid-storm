package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.vehicle.VehiclePhysicsPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.vehicle.VehiclePhysicsPacket} is
 * processed on the server.
 */
public class VehiclePhysicsPacketEvent extends PacketEvent {

    public VehiclePhysicsPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public VehiclePhysicsPacket getPacket() {
        return (VehiclePhysicsPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "VehiclePhysicsPacketEvent";
    }
}
