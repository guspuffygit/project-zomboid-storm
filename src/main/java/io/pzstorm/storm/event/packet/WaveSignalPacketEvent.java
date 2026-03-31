package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.WaveSignalPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.WaveSignalPacket} is processed on the
 * server.
 */
public class WaveSignalPacketEvent extends PacketEvent {

    public WaveSignalPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public WaveSignalPacket getPacket() {
        return (WaveSignalPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "WaveSignalPacketEvent";
    }
}
