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

    /**
     * {@code processServer} rewrites {@code type} to {@code FullData} when it answers a request
     * whose payload fits one packet, so handlers must see the values as received.
     */
    @Override
    public void capturePreState() {
        getField("type");
        getField("id");
    }

    public RequestDataPacket getPacket() {
        return (RequestDataPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RequestDataPacketEvent";
    }
}
