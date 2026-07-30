package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.faction.FactionDisbandPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.faction.FactionDisbandPacket} is
 * processed on the server.
 */
public class FactionDisbandPacketEvent extends PacketEvent {

    public FactionDisbandPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public FactionDisbandPacket getPacket() {
        return (FactionDisbandPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "FactionDisbandPacketEvent";
    }
}
