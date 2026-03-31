package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.iso.areas.SafeHouse;
import zombie.network.packets.safehouse.SafehouseChangeRespawnPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafehouseChangeRespawnPacket}
 * is processed on the server.
 */
public class SafehouseChangeRespawnPacketEvent extends PacketEvent {

    public SafehouseChangeRespawnPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafehouseChangeRespawnPacket getPacket() {
        return (SafehouseChangeRespawnPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SafehouseChangeRespawnPacketEvent";
    }

    public SafeHouse getSafehouse() {
        return getPacket().getSafehouse();
    }

    public String getPlayer() {
        return getPacket().player;
    }

    public boolean doRemove() {
        return getPacket().doRemove;
    }
}
