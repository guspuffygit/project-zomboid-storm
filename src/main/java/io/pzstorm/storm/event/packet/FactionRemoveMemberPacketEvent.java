package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.faction.FactionRemoveMemberPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.faction.FactionRemoveMemberPacket} is
 * processed on the server.
 */
public class FactionRemoveMemberPacketEvent extends PacketEvent {

    public FactionRemoveMemberPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public FactionRemoveMemberPacket getPacket() {
        return (FactionRemoveMemberPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "FactionRemoveMemberPacketEvent";
    }
}
