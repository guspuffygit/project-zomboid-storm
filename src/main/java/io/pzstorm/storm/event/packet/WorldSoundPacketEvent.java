package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.sound.WorldSoundPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.sound.WorldSoundPacket} is processed on
 * the server.
 */
public class WorldSoundPacketEvent extends PacketEvent {

    public WorldSoundPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public WorldSoundPacket getPacket() {
        return (WorldSoundPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "WorldSoundPacketEvent";
    }
}
