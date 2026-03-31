package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.StartFirePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.StartFirePacket} is processed on the
 * server.
 */
public class StartFirePacketEvent extends PacketEvent {

    public StartFirePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public StartFirePacket getPacket() {
        return (StartFirePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "StartFirePacketEvent";
    }
}
