package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.SyncClothingPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.SyncClothingPacket} is processed on the
 * server.
 */
public class SyncClothingPacketEvent extends PacketEvent {

    public SyncClothingPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SyncClothingPacket getPacket() {
        return (SyncClothingPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SyncClothingPacketEvent";
    }
}
