package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.service.ReceiveModDataPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.service.ReceiveModDataPacket} is
 * processed on the server.
 */
public class ReceiveModDataPacketEvent extends PacketEvent {

    public ReceiveModDataPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ReceiveModDataPacket getPacket() {
        return (ReceiveModDataPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ReceiveModDataPacketEvent";
    }
}
