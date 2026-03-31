package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.actions.WakeUpPlayerPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.actions.WakeUpPlayerPacket} is
 * processed on the server.
 */
public class WakeUpPlayerPacketEvent extends PacketEvent {

    public WakeUpPlayerPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public WakeUpPlayerPacket getPacket() {
        return (WakeUpPlayerPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "WakeUpPlayerPacketEvent";
    }
}
