package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.service.ReceiveContainerModDataPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.service.ReceiveContainerModDataPacket}
 * is processed on the server.
 */
public class ReceiveContainerModDataPacketEvent extends PacketEvent {

    public ReceiveContainerModDataPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ReceiveContainerModDataPacket getPacket() {
        return (ReceiveContainerModDataPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ReceiveContainerModDataPacketEvent";
    }
}
