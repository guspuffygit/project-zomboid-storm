package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.service.PlayerInventoryPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.service.PlayerInventoryPacket} is
 * processed on the server.
 */
public class PlayerInventoryPacketEvent extends PacketEvent {

    public PlayerInventoryPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public PlayerInventoryPacket getPacket() {
        return (PlayerInventoryPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "PlayerInventoryPacketEvent";
    }
}
