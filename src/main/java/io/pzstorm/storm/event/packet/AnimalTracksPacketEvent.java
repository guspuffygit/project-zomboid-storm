package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.AnimalTracksPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.character.AnimalTracksPacket} is
 * processed on the server.
 */
public class AnimalTracksPacketEvent extends PacketEvent {

    public AnimalTracksPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AnimalTracksPacket getPacket() {
        return (AnimalTracksPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AnimalTracksPacketEvent";
    }
}
