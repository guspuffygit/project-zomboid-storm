package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.world.DebugStoryPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.world.DebugStoryPacket} is processed on
 * the server.
 */
public class DebugStoryPacketEvent extends PacketEvent {

    public DebugStoryPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public DebugStoryPacket getPacket() {
        return (DebugStoryPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "DebugStoryPacketEvent";
    }
}
