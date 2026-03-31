package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.hit.AttackCollisionCheckPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.hit.AttackCollisionCheckPacket} is
 * processed on the server.
 */
public class AttackCollisionCheckPacketEvent extends PacketEvent {

    public AttackCollisionCheckPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AttackCollisionCheckPacket getPacket() {
        return (AttackCollisionCheckPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AttackCollisionCheckPacketEvent";
    }
}
