package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.actions.BurnCorpsePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.actions.BurnCorpsePacket} is processed
 * on the server.
 */
public class BurnCorpsePacketEvent extends PacketEvent {

    public BurnCorpsePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public BurnCorpsePacket getPacket() {
        return (BurnCorpsePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "BurnCorpsePacketEvent";
    }
}
