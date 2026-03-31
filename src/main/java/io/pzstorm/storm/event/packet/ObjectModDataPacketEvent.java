package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.ObjectModDataPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.ObjectModDataPacket} is processed on
 * the server.
 */
public class ObjectModDataPacketEvent extends PacketEvent {

    public ObjectModDataPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ObjectModDataPacket getPacket() {
        return (ObjectModDataPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ObjectModDataPacketEvent";
    }
}
