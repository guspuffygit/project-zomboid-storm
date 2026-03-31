package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.AnimalCommandPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.character.AnimalCommandPacket} is
 * processed on the server.
 */
public class AnimalCommandPacketEvent extends PacketEvent {

    public AnimalCommandPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AnimalCommandPacket getPacket() {
        return (AnimalCommandPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AnimalCommandPacketEvent";
    }
}
