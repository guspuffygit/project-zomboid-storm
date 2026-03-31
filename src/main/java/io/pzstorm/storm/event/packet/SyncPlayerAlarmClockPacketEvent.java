package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.SyncPlayerAlarmClockPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.SyncPlayerAlarmClockPacket} is
 * processed on the server.
 */
public class SyncPlayerAlarmClockPacketEvent extends PacketEvent {

    public SyncPlayerAlarmClockPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SyncPlayerAlarmClockPacket getPacket() {
        return (SyncPlayerAlarmClockPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SyncPlayerAlarmClockPacketEvent";
    }
}
