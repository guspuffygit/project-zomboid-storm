package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.faction.FactionInvitePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.faction.FactionInvitePacket} is
 * processed on the server.
 */
public class FactionInvitePacketEvent extends PacketEvent {

    public FactionInvitePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public FactionInvitePacket getPacket() {
        return (FactionInvitePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "FactionInvitePacketEvent";
    }
}
