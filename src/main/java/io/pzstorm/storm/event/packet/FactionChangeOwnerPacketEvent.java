package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.faction.FactionChangeOwnerPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.faction.FactionChangeOwnerPacket} is
 * processed on the server.
 */
public class FactionChangeOwnerPacketEvent extends PacketEvent {

    public FactionChangeOwnerPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public FactionChangeOwnerPacket getPacket() {
        return (FactionChangeOwnerPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "FactionChangeOwnerPacketEvent";
    }
}
