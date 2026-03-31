package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.actions.SmashWindowPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.actions.SmashWindowPacket} is processed
 * on the server.
 */
public class SmashWindowPacketEvent extends PacketEvent {

    public SmashWindowPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SmashWindowPacket getPacket() {
        return (SmashWindowPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SmashWindowPacketEvent";
    }
}
