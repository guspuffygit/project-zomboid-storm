package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.AddWarningPointPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.AddWarningPointPacket} is processed on
 * the server.
 */
public class AddWarningPointPacketEvent extends PacketEvent {

    public AddWarningPointPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AddWarningPointPacket getPacket() {
        return (AddWarningPointPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AddWarningPointPacketEvent";
    }
}
