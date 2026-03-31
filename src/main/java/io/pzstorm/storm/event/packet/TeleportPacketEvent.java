package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.TeleportPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.TeleportPacket} is processed on the
 * server.
 */
public class TeleportPacketEvent extends PacketEvent {

    public TeleportPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public TeleportPacket getPacket() {
        return (TeleportPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "TeleportPacketEvent";
    }
}
