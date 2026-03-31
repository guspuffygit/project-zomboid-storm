package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.RemoveCorpseFromMapPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.character.RemoveCorpseFromMapPacket} is
 * processed on the server.
 */
public class RemoveCorpseFromMapPacketEvent extends PacketEvent {

    public RemoveCorpseFromMapPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RemoveCorpseFromMapPacket getPacket() {
        return (RemoveCorpseFromMapPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RemoveCorpseFromMapPacketEvent";
    }
}
