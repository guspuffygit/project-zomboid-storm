package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.ForageItemFoundPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.character.ForageItemFoundPacket} is
 * processed on the server.
 */
public class ForageItemFoundPacketEvent extends PacketEvent {

    public ForageItemFoundPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ForageItemFoundPacket getPacket() {
        return (ForageItemFoundPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ForageItemFoundPacketEvent";
    }
}
