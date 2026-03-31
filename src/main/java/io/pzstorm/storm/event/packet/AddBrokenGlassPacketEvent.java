package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.AddBrokenGlassPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.AddBrokenGlassPacket} is processed on
 * the server.
 */
public class AddBrokenGlassPacketEvent extends PacketEvent {

    public AddBrokenGlassPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AddBrokenGlassPacket getPacket() {
        return (AddBrokenGlassPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AddBrokenGlassPacketEvent";
    }
}
