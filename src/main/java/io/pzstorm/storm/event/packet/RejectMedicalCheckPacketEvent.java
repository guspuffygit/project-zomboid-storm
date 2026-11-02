package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RejectMedicalCheckPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.RejectMedicalCheckPacket} is processed
 * on the server.
 */
public class RejectMedicalCheckPacketEvent extends PacketEvent {

    public RejectMedicalCheckPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RejectMedicalCheckPacket getPacket() {
        return (RejectMedicalCheckPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RejectMedicalCheckPacketEvent";
    }
}
