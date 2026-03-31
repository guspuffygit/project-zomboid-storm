package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.actions.StatePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.actions.StatePacket} is processed on
 * the server.
 */
public class StatePacketEvent extends PacketEvent {

    public StatePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public StatePacket getPacket() {
        return (StatePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "StatePacketEvent";
    }
}
