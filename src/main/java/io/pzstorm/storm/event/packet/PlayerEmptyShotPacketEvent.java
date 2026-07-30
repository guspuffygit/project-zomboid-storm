package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.PlayerEmptyShotPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.character.PlayerEmptyShotPacket} is
 * processed on the server.
 */
public class PlayerEmptyShotPacketEvent extends PacketEvent {

    public PlayerEmptyShotPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public PlayerEmptyShotPacket getPacket() {
        return (PlayerEmptyShotPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "PlayerEmptyShotPacketEvent";
    }
}
