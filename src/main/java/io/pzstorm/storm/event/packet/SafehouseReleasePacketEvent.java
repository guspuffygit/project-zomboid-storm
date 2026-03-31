package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.safehouse.SafehouseReleasePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafehouseReleasePacket} is
 * processed on the server.
 */
public class SafehouseReleasePacketEvent extends PacketEvent {

    public SafehouseReleasePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafehouseReleasePacket getPacket() {
        return (SafehouseReleasePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SafehouseReleasePacketEvent";
    }
}
