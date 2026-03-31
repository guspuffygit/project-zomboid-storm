package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.safehouse.SafehouseClaimPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafehouseClaimPacket} is
 * processed on the server.
 */
public class SafehouseClaimPacketEvent extends PacketEvent {

    public SafehouseClaimPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafehouseClaimPacket getPacket() {
        return (SafehouseClaimPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SafehouseClaimPacketEvent";
    }
}
