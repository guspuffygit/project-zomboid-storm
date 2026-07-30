package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.sound.LoopedRangedWeaponSoundPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.sound.LoopedRangedWeaponSoundPacket} is
 * processed on the server.
 */
public class LoopedRangedWeaponSoundPacketEvent extends PacketEvent {

    public LoopedRangedWeaponSoundPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public LoopedRangedWeaponSoundPacket getPacket() {
        return (LoopedRangedWeaponSoundPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "LoopedRangedWeaponSoundPacketEvent";
    }
}
