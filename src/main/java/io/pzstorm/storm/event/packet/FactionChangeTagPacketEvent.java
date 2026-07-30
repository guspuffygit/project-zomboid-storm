package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.faction.FactionChangeTagPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.faction.FactionChangeTagPacket} is
 * processed on the server.
 */
public class FactionChangeTagPacketEvent extends PacketEvent {

    public FactionChangeTagPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public FactionChangeTagPacket getPacket() {
        return (FactionChangeTagPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "FactionChangeTagPacketEvent";
    }
}
