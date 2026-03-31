package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.safehouse.SafezoneClaimPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafezoneClaimPacket} is
 * processed on the server.
 */
public class SafezoneClaimPacketEvent extends PacketEvent {

    public SafezoneClaimPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafezoneClaimPacket getPacket() {
        return (SafezoneClaimPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SafezoneClaimPacketEvent";
    }
}
