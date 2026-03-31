package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.CreatePlayerPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.character.CreatePlayerPacket} is
 * processed on the server.
 */
public class CreatePlayerPacketEvent extends PacketEvent {

    public CreatePlayerPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public CreatePlayerPacket getPacket() {
        return (CreatePlayerPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "CreatePlayerPacketEvent";
    }
}
