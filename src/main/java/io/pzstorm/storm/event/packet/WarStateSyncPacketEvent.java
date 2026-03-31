package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.WarStateSyncPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.WarStateSyncPacket} is processed on the
 * server.
 */
public class WarStateSyncPacketEvent extends PacketEvent {

    public WarStateSyncPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public WarStateSyncPacket getPacket() {
        return (WarStateSyncPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "WarStateSyncPacketEvent";
    }
}
