package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RequestDataPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.RequestDataPacket} is processed on the
 * server.
 */
public class RequestDataPacketEvent extends PacketEvent {

    public RequestDataPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RequestDataPacket getPacket() {
        return (RequestDataPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RequestDataPacketEvent";
    }
}
