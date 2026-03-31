package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.service.ScoreboardUpdatePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.service.ScoreboardUpdatePacket} is
 * processed on the server.
 */
public class ScoreboardUpdatePacketEvent extends PacketEvent {

    public ScoreboardUpdatePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ScoreboardUpdatePacket getPacket() {
        return (ScoreboardUpdatePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ScoreboardUpdatePacketEvent";
    }
}
