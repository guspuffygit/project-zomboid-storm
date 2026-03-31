package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.ItemStatsPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.ItemStatsPacket} is processed on the
 * server.
 */
public class ItemStatsPacketEvent extends PacketEvent {

    public ItemStatsPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ItemStatsPacket getPacket() {
        return (ItemStatsPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ItemStatsPacketEvent";
    }
}
