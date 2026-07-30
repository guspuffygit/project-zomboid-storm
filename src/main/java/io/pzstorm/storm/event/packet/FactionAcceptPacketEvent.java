package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.faction.FactionAcceptPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.faction.FactionAcceptPacket} is
 * processed on the server.
 */
public class FactionAcceptPacketEvent extends PacketEvent {

    public FactionAcceptPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public FactionAcceptPacket getPacket() {
        return (FactionAcceptPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "FactionAcceptPacketEvent";
    }
}
