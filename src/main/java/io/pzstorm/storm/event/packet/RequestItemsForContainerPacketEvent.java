package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RequestItemsForContainerPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.RequestItemsForContainerPacket} is
 * processed on the server.
 */
public class RequestItemsForContainerPacketEvent extends PacketEvent {

    public RequestItemsForContainerPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RequestItemsForContainerPacket getPacket() {
        return (RequestItemsForContainerPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RequestItemsForContainerPacketEvent";
    }
}
