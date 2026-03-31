package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.TradingUIAddItemPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.TradingUIAddItemPacket} is processed on
 * the server.
 */
public class TradingUIAddItemPacketEvent extends PacketEvent {

    public TradingUIAddItemPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public TradingUIAddItemPacket getPacket() {
        return (TradingUIAddItemPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "TradingUIAddItemPacketEvent";
    }
}
