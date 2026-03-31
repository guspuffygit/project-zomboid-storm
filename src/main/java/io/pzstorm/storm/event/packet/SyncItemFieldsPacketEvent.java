package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.SyncItemFieldsPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.SyncItemFieldsPacket} is processed on
 * the server.
 */
public class SyncItemFieldsPacketEvent extends PacketEvent {

    public SyncItemFieldsPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SyncItemFieldsPacket getPacket() {
        return (SyncItemFieldsPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SyncItemFieldsPacketEvent";
    }
}
