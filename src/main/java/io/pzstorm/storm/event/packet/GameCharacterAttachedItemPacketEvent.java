package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.GameCharacterAttachedItemPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.GameCharacterAttachedItemPacket} is
 * processed on the server.
 */
public class GameCharacterAttachedItemPacketEvent extends PacketEvent {

    public GameCharacterAttachedItemPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public GameCharacterAttachedItemPacket getPacket() {
        return (GameCharacterAttachedItemPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "GameCharacterAttachedItemPacketEvent";
    }
}
