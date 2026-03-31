package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.sound.StopSoundPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.sound.StopSoundPacket} is processed on
 * the server.
 */
public class StopSoundPacketEvent extends PacketEvent {

    public StopSoundPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public StopSoundPacket getPacket() {
        return (StopSoundPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "StopSoundPacketEvent";
    }
}
