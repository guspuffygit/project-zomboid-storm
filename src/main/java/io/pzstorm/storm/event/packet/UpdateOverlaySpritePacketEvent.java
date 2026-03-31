package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.UpdateOverlaySpritePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.UpdateOverlaySpritePacket} is processed
 * on the server.
 */
public class UpdateOverlaySpritePacketEvent extends PacketEvent {

    public UpdateOverlaySpritePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public UpdateOverlaySpritePacket getPacket() {
        return (UpdateOverlaySpritePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "UpdateOverlaySpritePacketEvent";
    }
}
