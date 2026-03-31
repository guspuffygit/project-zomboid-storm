package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RequestTradingPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.RequestTradingPacket} is processed on
 * the server.
 */
public class RequestTradingPacketEvent extends PacketEvent {

    public RequestTradingPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RequestTradingPacket getPacket() {
        return (RequestTradingPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RequestTradingPacketEvent";
    }
}
