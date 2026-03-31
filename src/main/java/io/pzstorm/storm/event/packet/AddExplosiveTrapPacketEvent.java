package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.AddExplosiveTrapPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.AddExplosiveTrapPacket} is processed on
 * the server.
 */
public class AddExplosiveTrapPacketEvent extends PacketEvent {

    public AddExplosiveTrapPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AddExplosiveTrapPacket getPacket() {
        return (AddExplosiveTrapPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AddExplosiveTrapPacketEvent";
    }
}
