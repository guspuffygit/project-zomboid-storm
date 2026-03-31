package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.TradingUIUpdateStatePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.TradingUIUpdateStatePacket} is
 * processed on the server.
 */
public class TradingUIUpdateStatePacketEvent extends PacketEvent {

    public TradingUIUpdateStatePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public TradingUIUpdateStatePacket getPacket() {
        return (TradingUIUpdateStatePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "TradingUIUpdateStatePacketEvent";
    }
}
