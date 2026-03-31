package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.connection.GoogleAuthPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.connection.GoogleAuthPacket} is
 * processed on the server.
 */
public class GoogleAuthPacketEvent extends PacketEvent {

    public GoogleAuthPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public GoogleAuthPacket getPacket() {
        return (GoogleAuthPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "GoogleAuthPacketEvent";
    }
}
