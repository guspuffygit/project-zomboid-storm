package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.StopFirePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.StopFirePacket} is processed on the
 * server.
 */
public class StopFirePacketEvent extends PacketEvent {

    public StopFirePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public StopFirePacket getPacket() {
        return (StopFirePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "StopFirePacketEvent";
    }
}
