package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.BodyDamageUpdatePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.BodyDamageUpdatePacket} is processed on
 * the server.
 */
public class BodyDamageUpdatePacketEvent extends PacketEvent {

    public BodyDamageUpdatePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public BodyDamageUpdatePacket getPacket() {
        return (BodyDamageUpdatePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "BodyDamageUpdatePacketEvent";
    }
}
