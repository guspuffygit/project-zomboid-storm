package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.EquipPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.EquipPacket} is processed on the
 * server.
 */
public class EquipPacketEvent extends PacketEvent {

    public EquipPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public EquipPacket getPacket() {
        return (EquipPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "EquipPacketEvent";
    }
}
