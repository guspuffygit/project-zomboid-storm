package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.ItemTransactionPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.ItemTransactionPacket} is processed on
 * the server.
 */
public class ItemTransactionPacketEvent extends PacketEvent {

    public ItemTransactionPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ItemTransactionPacket getPacket() {
        return (ItemTransactionPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ItemTransactionPacketEvent";
    }
}
