package io.pzstorm.storm.event.packet;

import javax.annotation.Nullable;
import zombie.core.raknet.UdpConnection;
import zombie.iso.areas.SafeHouse;
import zombie.network.packets.safehouse.SafehouseAcceptPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafehouseAcceptPacket} is
 * processed on the server.
 */
public class SafehouseAcceptPacketEvent extends PacketEvent {

    public SafehouseAcceptPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafehouseAcceptPacket getPacket() {
        return (SafehouseAcceptPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SafehouseAcceptPacketEvent";
    }

    public SafeHouse getSafehouse() {
        return getPacket().getSafehouse();
    }

    public @Nullable String getOwner() {
        return (String) getField("owner");
    }

    public @Nullable String getInvited() {
        return (String) getField("invited");
    }

    public @Nullable Boolean isAccepted() {
        return (Boolean) getField("isAccepted");
    }
}
