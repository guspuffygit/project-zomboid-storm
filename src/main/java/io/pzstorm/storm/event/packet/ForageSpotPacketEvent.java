package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.foraging.ForageSpotPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.foraging.ForageSpotPacket} is processed
 * on the server.
 */
public class ForageSpotPacketEvent extends PacketEvent {

    public ForageSpotPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ForageSpotPacket getPacket() {
        return (ForageSpotPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ForageSpotPacketEvent";
    }
}
