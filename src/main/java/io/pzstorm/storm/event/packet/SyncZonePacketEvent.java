package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.SyncZonePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.SyncZonePacket} is processed on the
 * server.
 */
public class SyncZonePacketEvent extends PacketEvent {

    public SyncZonePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SyncZonePacket getPacket() {
        return (SyncZonePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SyncZonePacketEvent";
    }
}
