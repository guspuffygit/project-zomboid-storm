package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RequestNetworkUsersPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.RequestNetworkUsersPacket} is processed
 * on the server.
 */
public class RequestNetworkUsersPacketEvent extends PacketEvent {

    public RequestNetworkUsersPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RequestNetworkUsersPacket getPacket() {
        return (RequestNetworkUsersPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RequestNetworkUsersPacketEvent";
    }
}
