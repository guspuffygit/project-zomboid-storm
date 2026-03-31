package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.actions.RemoveBloodPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.actions.RemoveBloodPacket} is processed
 * on the server.
 */
public class RemoveBloodPacketEvent extends PacketEvent {

    public RemoveBloodPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RemoveBloodPacket getPacket() {
        return (RemoveBloodPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RemoveBloodPacketEvent";
    }
}
