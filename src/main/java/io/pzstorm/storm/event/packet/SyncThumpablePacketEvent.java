package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.SyncThumpablePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.SyncThumpablePacket} is processed on
 * the server.
 */
public class SyncThumpablePacketEvent extends PacketEvent {

    public SyncThumpablePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SyncThumpablePacket getPacket() {
        return (SyncThumpablePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SyncThumpablePacketEvent";
    }
}
