package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.hit.HitCharacter;

/**
 * Typed event dispatched when {@link zombie.network.packets.hit.HitCharacter} is processed on the
 * server.
 */
public class HitCharacterEvent extends PacketEvent {

    public HitCharacterEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public HitCharacter getPacket() {
        return (HitCharacter) getRawPacket();
    }

    @Override
    public String getName() {
        return "HitCharacterEvent";
    }
}
