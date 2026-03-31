package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.FishingActionPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.FishingActionPacket} is processed on
 * the server.
 */
public class FishingActionPacketEvent extends PacketEvent {

    public FishingActionPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public FishingActionPacket getPacket() {
        return (FishingActionPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "FishingActionPacketEvent";
    }
}
