package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.connection.GoogleAuthKeyPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.connection.GoogleAuthKeyPacket} is
 * processed on the server.
 */
public class GoogleAuthKeyPacketEvent extends PacketEvent {

    public GoogleAuthKeyPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public GoogleAuthKeyPacket getPacket() {
        return (GoogleAuthKeyPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "GoogleAuthKeyPacketEvent";
    }
}
