package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RegisterZonePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.RegisterZonePacket} is processed on the
 * server.
 */
public class RegisterZonePacketEvent extends PacketEvent {

    public RegisterZonePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RegisterZonePacket getPacket() {
        return (RegisterZonePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RegisterZonePacketEvent";
    }
}
