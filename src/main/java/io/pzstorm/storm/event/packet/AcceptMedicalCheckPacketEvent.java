package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.AcceptMedicalCheckPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.AcceptMedicalCheckPacket} is processed
 * on the server.
 */
public class AcceptMedicalCheckPacketEvent extends PacketEvent {

    public AcceptMedicalCheckPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AcceptMedicalCheckPacket getPacket() {
        return (AcceptMedicalCheckPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AcceptMedicalCheckPacketEvent";
    }
}
