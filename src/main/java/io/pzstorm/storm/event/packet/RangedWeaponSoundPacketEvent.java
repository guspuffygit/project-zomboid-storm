package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.sound.RangedWeaponSoundPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.sound.RangedWeaponSoundPacket} is
 * processed on the server.
 */
public class RangedWeaponSoundPacketEvent extends PacketEvent {

    public RangedWeaponSoundPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RangedWeaponSoundPacket getPacket() {
        return (RangedWeaponSoundPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RangedWeaponSoundPacketEvent";
    }
}
