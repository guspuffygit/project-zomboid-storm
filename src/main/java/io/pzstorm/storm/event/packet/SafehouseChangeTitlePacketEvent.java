package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.iso.areas.SafeHouse;
import zombie.network.packets.safehouse.SafehouseChangeTitlePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafehouseChangeTitlePacket}
 * is processed on the server.
 */
public class SafehouseChangeTitlePacketEvent extends PacketEvent {

    public SafehouseChangeTitlePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafehouseChangeTitlePacket getPacket() {
        return (SafehouseChangeTitlePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SafehouseChangeTitlePacketEvent";
    }

    public SafeHouse getSafehouse() {
        return getPacket().getSafehouse();
    }

    public String getTitle() {
        return getPacket().title;
    }
}
