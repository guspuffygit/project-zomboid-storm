package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.SyncNonPvpZonePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.SyncNonPvpZonePacket} is processed on
 * the server.
 */
public class SyncNonPvpZonePacketEvent extends PacketEvent {

    public SyncNonPvpZonePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SyncNonPvpZonePacket getPacket() {
        return (SyncNonPvpZonePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SyncNonPvpZonePacketEvent";
    }
}
