package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.faction.FactionCreatePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.faction.FactionCreatePacket} is
 * processed on the server.
 */
public class FactionCreatePacketEvent extends PacketEvent {

    public FactionCreatePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public FactionCreatePacket getPacket() {
        return (FactionCreatePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "FactionCreatePacketEvent";
    }
}
