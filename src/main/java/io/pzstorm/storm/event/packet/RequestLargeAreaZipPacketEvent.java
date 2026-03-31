package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RequestLargeAreaZipPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.RequestLargeAreaZipPacket} is processed
 * on the server.
 */
public class RequestLargeAreaZipPacketEvent extends PacketEvent {

    public RequestLargeAreaZipPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RequestLargeAreaZipPacket getPacket() {
        return (RequestLargeAreaZipPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RequestLargeAreaZipPacketEvent";
    }
}
