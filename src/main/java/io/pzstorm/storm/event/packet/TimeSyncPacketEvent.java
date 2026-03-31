package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.service.TimeSyncPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.service.TimeSyncPacket} is processed on
 * the server.
 */
public class TimeSyncPacketEvent extends PacketEvent {

    public TimeSyncPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public TimeSyncPacket getPacket() {
        return (TimeSyncPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "TimeSyncPacketEvent";
    }
}
