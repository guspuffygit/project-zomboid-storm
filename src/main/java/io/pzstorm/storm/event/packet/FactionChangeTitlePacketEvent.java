package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.faction.FactionChangeTitlePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.faction.FactionChangeTitlePacket} is
 * processed on the server.
 */
public class FactionChangeTitlePacketEvent extends PacketEvent {

    public FactionChangeTitlePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public FactionChangeTitlePacket getPacket() {
        return (FactionChangeTitlePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "FactionChangeTitlePacketEvent";
    }
}
