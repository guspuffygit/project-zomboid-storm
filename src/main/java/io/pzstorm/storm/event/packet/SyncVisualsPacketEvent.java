package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.SyncVisualsPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.SyncVisualsPacket} is processed on the
 * server.
 */
public class SyncVisualsPacketEvent extends PacketEvent {

    public SyncVisualsPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SyncVisualsPacket getPacket() {
        return (SyncVisualsPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SyncVisualsPacketEvent";
    }
}
