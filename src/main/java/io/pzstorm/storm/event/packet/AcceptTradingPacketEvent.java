package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.AcceptTradingPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.AcceptTradingPacket} is processed on
 * the server.
 */
public class AcceptTradingPacketEvent extends PacketEvent {

    public AcceptTradingPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AcceptTradingPacket getPacket() {
        return (AcceptTradingPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AcceptTradingPacketEvent";
    }
}
