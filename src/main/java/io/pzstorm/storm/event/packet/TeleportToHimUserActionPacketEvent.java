package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.TeleportToHimUserActionPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.TeleportToHimUserActionPacket} is
 * processed on the server.
 */
public class TeleportToHimUserActionPacketEvent extends PacketEvent {

    public TeleportToHimUserActionPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public TeleportToHimUserActionPacket getPacket() {
        return (TeleportToHimUserActionPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "TeleportToHimUserActionPacketEvent";
    }
}
