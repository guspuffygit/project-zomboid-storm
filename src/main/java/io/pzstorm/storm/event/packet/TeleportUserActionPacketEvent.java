package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.TeleportUserActionPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.TeleportUserActionPacket} is processed
 * on the server.
 */
public class TeleportUserActionPacketEvent extends PacketEvent {

    public TeleportUserActionPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public TeleportUserActionPacket getPacket() {
        return (TeleportUserActionPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "TeleportUserActionPacketEvent";
    }
}
