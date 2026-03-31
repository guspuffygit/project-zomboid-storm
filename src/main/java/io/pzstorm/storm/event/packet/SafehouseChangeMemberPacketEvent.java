package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.iso.areas.SafeHouse;
import zombie.network.packets.safehouse.SafehouseChangeMemberPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafehouseChangeMemberPacket}
 * is processed on the server.
 */
public class SafehouseChangeMemberPacketEvent extends PacketEvent {

    public SafehouseChangeMemberPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafehouseChangeMemberPacket getPacket() {
        return (SafehouseChangeMemberPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SafehouseChangeMemberPacketEvent";
    }

    public SafeHouse getSafehouse() {
        return getPacket().getSafehouse();
    }

    public String getPlayer() {
        return getPacket().player;
    }
}
