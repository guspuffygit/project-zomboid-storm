package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.SyncWorldAlarmClockPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.SyncWorldAlarmClockPacket} is processed
 * on the server.
 */
public class SyncWorldAlarmClockPacketEvent extends PacketEvent {

    public SyncWorldAlarmClockPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SyncWorldAlarmClockPacket getPacket() {
        return (SyncWorldAlarmClockPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SyncWorldAlarmClockPacketEvent";
    }
}
