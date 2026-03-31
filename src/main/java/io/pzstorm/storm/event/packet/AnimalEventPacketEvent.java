package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.actions.AnimalEventPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.actions.AnimalEventPacket} is processed
 * on the server.
 */
public class AnimalEventPacketEvent extends PacketEvent {

    public AnimalEventPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AnimalEventPacket getPacket() {
        return (AnimalEventPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AnimalEventPacketEvent";
    }
}
