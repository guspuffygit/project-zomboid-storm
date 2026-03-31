package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.WorldMessagePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.WorldMessagePacket} is processed on the
 * server.
 */
public class WorldMessagePacketEvent extends PacketEvent {

    public WorldMessagePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public WorldMessagePacket getPacket() {
        return (WorldMessagePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "WorldMessagePacketEvent";
    }
}
