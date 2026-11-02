package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RejectTradingPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.RejectTradingPacket} is processed on
 * the server.
 */
public class RejectTradingPacketEvent extends PacketEvent {

    public RejectTradingPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RejectTradingPacket getPacket() {
        return (RejectTradingPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RejectTradingPacketEvent";
    }
}
