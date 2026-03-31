package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.TradingUIRemoveItemPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.TradingUIRemoveItemPacket} is processed
 * on the server.
 */
public class TradingUIRemoveItemPacketEvent extends PacketEvent {

    public TradingUIRemoveItemPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public TradingUIRemoveItemPacket getPacket() {
        return (TradingUIRemoveItemPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "TradingUIRemoveItemPacketEvent";
    }
}
