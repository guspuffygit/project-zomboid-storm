package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.actions.SneezeCoughPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.actions.SneezeCoughPacket} is processed
 * on the server.
 */
public class SneezeCoughPacketEvent extends PacketEvent {

    public SneezeCoughPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SneezeCoughPacket getPacket() {
        return (SneezeCoughPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SneezeCoughPacketEvent";
    }
}
