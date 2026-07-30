package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.faction.FactionStatsPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.faction.FactionStatsPacket} is
 * processed on the server.
 */
public class FactionStatsPacketEvent extends PacketEvent {

    public FactionStatsPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public FactionStatsPacket getPacket() {
        return (FactionStatsPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "FactionStatsPacketEvent";
    }
}
