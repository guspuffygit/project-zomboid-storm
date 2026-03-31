package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.safehouse.SafehouseAcceptPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafehouseAcceptPacket} is
 * processed on the server.
 */
public class SafehouseAcceptPacketEvent extends PacketEvent {

    public SafehouseAcceptPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafehouseAcceptPacket getPacket() {
        return (SafehouseAcceptPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SafehouseAcceptPacketEvent";
    }
}
