package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.sound.PlayWorldSoundPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.sound.PlayWorldSoundPacket} is
 * processed on the server.
 */
public class PlayWorldSoundPacketEvent extends PacketEvent {

    public PlayWorldSoundPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public PlayWorldSoundPacket getPacket() {
        return (PlayWorldSoundPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "PlayWorldSoundPacketEvent";
    }
}
