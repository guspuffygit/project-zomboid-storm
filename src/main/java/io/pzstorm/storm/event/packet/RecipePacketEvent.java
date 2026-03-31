package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.service.RecipePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.service.RecipePacket} is processed on
 * the server.
 */
public class RecipePacketEvent extends PacketEvent {

    public RecipePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RecipePacket getPacket() {
        return (RecipePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RecipePacketEvent";
    }
}
