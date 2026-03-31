package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.connection.QueuePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.connection.QueuePacket} is processed on
 * the server.
 */
public class QueuePacketEvent extends PacketEvent {

    public QueuePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public QueuePacket getPacket() {
        return (QueuePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "QueuePacketEvent";
    }
}
