package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.safehouse.SafehouseChangeOwnerPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafehouseChangeOwnerPacket}
 * is processed on the server.
 */
public class SafehouseChangeOwnerPacketEvent extends PacketEvent {

    public SafehouseChangeOwnerPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafehouseChangeOwnerPacket getPacket() {
        return (SafehouseChangeOwnerPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SafehouseChangeOwnerPacketEvent";
    }
}
