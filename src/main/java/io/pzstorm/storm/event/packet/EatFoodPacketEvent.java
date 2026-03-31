package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.actions.EatFoodPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.actions.EatFoodPacket} is processed on
 * the server.
 */
public class EatFoodPacketEvent extends PacketEvent {

    public EatFoodPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public EatFoodPacket getPacket() {
        return (EatFoodPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "EatFoodPacketEvent";
    }
}
