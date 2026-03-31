package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RemoveInventoryItemFromContainerPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.RemoveInventoryItemFromContainerPacket}
 * is processed on the server.
 */
public class RemoveInventoryItemFromContainerPacketEvent extends PacketEvent {

    public RemoveInventoryItemFromContainerPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RemoveInventoryItemFromContainerPacket getPacket() {
        return (RemoveInventoryItemFromContainerPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RemoveInventoryItemFromContainerPacketEvent";
    }
}
