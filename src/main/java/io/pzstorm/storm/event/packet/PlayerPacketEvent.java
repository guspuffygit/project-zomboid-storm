package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.PlayerPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.character.PlayerPacket} is processed on
 * the server.
 */
public class PlayerPacketEvent extends PacketEvent {

    public PlayerPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public PlayerPacket getPacket() {
        return (PlayerPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "PlayerPacketEvent";
    }
}
