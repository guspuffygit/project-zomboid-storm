package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.AnimalUpdatePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.character.AnimalUpdatePacket} is
 * processed on the server.
 */
public class AnimalUpdatePacketEvent extends PacketEvent {

    public AnimalUpdatePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AnimalUpdatePacket getPacket() {
        return (AnimalUpdatePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AnimalUpdatePacketEvent";
    }
}
