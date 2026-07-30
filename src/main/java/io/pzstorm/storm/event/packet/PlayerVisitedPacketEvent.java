package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.PlayerVisitedPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.character.PlayerVisitedPacket} is
 * processed on the server.
 */
public class PlayerVisitedPacketEvent extends PacketEvent {

    public PlayerVisitedPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public PlayerVisitedPacket getPacket() {
        return (PlayerVisitedPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "PlayerVisitedPacketEvent";
    }
}
