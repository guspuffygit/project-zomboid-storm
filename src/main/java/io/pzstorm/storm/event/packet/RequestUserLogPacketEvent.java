package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.service.RequestUserLogPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.service.RequestUserLogPacket} is
 * processed on the server.
 */
public class RequestUserLogPacketEvent extends PacketEvent {

    public RequestUserLogPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RequestUserLogPacket getPacket() {
        return (RequestUserLogPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RequestUserLogPacketEvent";
    }
}
