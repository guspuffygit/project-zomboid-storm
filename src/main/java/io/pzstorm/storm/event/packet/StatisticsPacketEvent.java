package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.service.StatisticsPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.service.StatisticsPacket} is processed
 * on the server.
 */
public class StatisticsPacketEvent extends PacketEvent {

    public StatisticsPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public StatisticsPacket getPacket() {
        return (StatisticsPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "StatisticsPacketEvent";
    }
}
