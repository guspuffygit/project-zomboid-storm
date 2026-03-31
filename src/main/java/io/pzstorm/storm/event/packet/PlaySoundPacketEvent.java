package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.sound.PlaySoundPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.sound.PlaySoundPacket} is processed on
 * the server.
 */
public class PlaySoundPacketEvent extends PacketEvent {

    public PlaySoundPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public PlaySoundPacket getPacket() {
        return (PlaySoundPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "PlaySoundPacketEvent";
    }
}
