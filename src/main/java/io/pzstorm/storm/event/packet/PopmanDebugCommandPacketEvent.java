package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.service.PopmanDebugCommandPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.service.PopmanDebugCommandPacket} is
 * processed on the server.
 */
public class PopmanDebugCommandPacketEvent extends PacketEvent {

    public PopmanDebugCommandPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public PopmanDebugCommandPacket getPacket() {
        return (PopmanDebugCommandPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "PopmanDebugCommandPacketEvent";
    }
}
