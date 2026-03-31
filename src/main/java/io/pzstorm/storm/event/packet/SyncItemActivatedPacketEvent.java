package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.SyncItemActivatedPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.SyncItemActivatedPacket} is processed
 * on the server.
 */
public class SyncItemActivatedPacketEvent extends PacketEvent {

    public SyncItemActivatedPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SyncItemActivatedPacket getPacket() {
        return (SyncItemActivatedPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SyncItemActivatedPacketEvent";
    }
}
