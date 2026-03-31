package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.NetworkUserActionPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.NetworkUserActionPacket} is processed
 * on the server.
 */
public class NetworkUserActionPacketEvent extends PacketEvent {

    public NetworkUserActionPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public NetworkUserActionPacket getPacket() {
        return (NetworkUserActionPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "NetworkUserActionPacketEvent";
    }
}
