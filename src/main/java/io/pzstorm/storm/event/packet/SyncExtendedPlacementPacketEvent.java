package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.SyncExtendedPlacementPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.SyncExtendedPlacementPacket} is
 * processed on the server.
 */
public class SyncExtendedPlacementPacketEvent extends PacketEvent {

    public SyncExtendedPlacementPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SyncExtendedPlacementPacket getPacket() {
        return (SyncExtendedPlacementPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SyncExtendedPlacementPacketEvent";
    }
}
