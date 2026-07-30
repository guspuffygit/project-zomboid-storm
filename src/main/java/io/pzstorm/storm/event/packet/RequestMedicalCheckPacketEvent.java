package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RequestMedicalCheckPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.RequestMedicalCheckPacket} is processed
 * on the server.
 */
public class RequestMedicalCheckPacketEvent extends PacketEvent {

    public RequestMedicalCheckPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RequestMedicalCheckPacket getPacket() {
        return (RequestMedicalCheckPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RequestMedicalCheckPacketEvent";
    }
}
