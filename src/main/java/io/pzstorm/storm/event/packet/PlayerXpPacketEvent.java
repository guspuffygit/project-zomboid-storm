package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.PlayerXpPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.PlayerXpPacket} is processed on the
 * server.
 */
public class PlayerXpPacketEvent extends PacketEvent {

    public PlayerXpPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public PlayerXpPacket getPacket() {
        return (PlayerXpPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "PlayerXpPacketEvent";
    }
}
