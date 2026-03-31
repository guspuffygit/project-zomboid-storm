package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.PlayerDataRequestPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.character.PlayerDataRequestPacket} is
 * processed on the server.
 */
public class PlayerDataRequestPacketEvent extends PacketEvent {

    public PlayerDataRequestPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public PlayerDataRequestPacket getPacket() {
        return (PlayerDataRequestPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "PlayerDataRequestPacketEvent";
    }
}
