package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.GeneralActionPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.GeneralActionPacket} is processed on
 * the server.
 */
public class GeneralActionPacketEvent extends PacketEvent {

    public GeneralActionPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public GeneralActionPacket getPacket() {
        return (GeneralActionPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "GeneralActionPacketEvent";
    }
}
