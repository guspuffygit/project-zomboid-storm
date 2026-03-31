package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.safehouse.SafehouseInvitePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafehouseInvitePacket} is
 * processed on the server.
 */
public class SafehouseInvitePacketEvent extends PacketEvent {

    public SafehouseInvitePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafehouseInvitePacket getPacket() {
        return (SafehouseInvitePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SafehouseInvitePacketEvent";
    }
}
