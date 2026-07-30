package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.foraging.ForageRequestZonePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.foraging.ForageRequestZonePacket} is
 * processed on the server.
 */
public class ForageRequestZonePacketEvent extends PacketEvent {

    public ForageRequestZonePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ForageRequestZonePacket getPacket() {
        return (ForageRequestZonePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ForageRequestZonePacketEvent";
    }
}
