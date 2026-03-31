package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.SyncCustomLightSettingsPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.SyncCustomLightSettingsPacket} is
 * processed on the server.
 */
public class SyncCustomLightSettingsPacketEvent extends PacketEvent {

    public SyncCustomLightSettingsPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SyncCustomLightSettingsPacket getPacket() {
        return (SyncCustomLightSettingsPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SyncCustomLightSettingsPacketEvent";
    }
}
