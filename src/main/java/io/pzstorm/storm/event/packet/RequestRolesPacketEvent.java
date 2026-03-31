package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RequestRolesPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.RequestRolesPacket} is processed on the
 * server.
 */
public class RequestRolesPacketEvent extends PacketEvent {

    public RequestRolesPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RequestRolesPacket getPacket() {
        return (RequestRolesPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RequestRolesPacketEvent";
    }
}
