package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.VariableSyncPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.VariableSyncPacket} is processed on the
 * server.
 */
public class VariableSyncPacketEvent extends PacketEvent {

    public VariableSyncPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public VariableSyncPacket getPacket() {
        return (VariableSyncPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "VariableSyncPacketEvent";
    }
}
