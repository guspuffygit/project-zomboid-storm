package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.SyncHandWeaponFieldsPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.SyncHandWeaponFieldsPacket} is
 * processed on the server.
 */
public class SyncHandWeaponFieldsPacketEvent extends PacketEvent {

    public SyncHandWeaponFieldsPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SyncHandWeaponFieldsPacket getPacket() {
        return (SyncHandWeaponFieldsPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SyncHandWeaponFieldsPacketEvent";
    }
}
