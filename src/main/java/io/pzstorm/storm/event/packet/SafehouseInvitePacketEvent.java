package io.pzstorm.storm.event.packet;

import javax.annotation.Nullable;
import zombie.core.raknet.UdpConnection;
import zombie.iso.areas.SafeHouse;
import zombie.network.packets.safehouse.SafehouseInvitePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafehouseInvitePacket} is
 * processed on the server.
 */
public class SafehouseInvitePacketEvent extends PacketEvent {

    public SafehouseInvitePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafehouseInvitePacket getPacket() {
        return (SafehouseInvitePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SafehouseInvitePacketEvent";
    }

    public SafeHouse getSafehouse() {
        return getPacket().getSafehouse();
    }

    public int getX() {
        return getPacket().x;
    }

    public int getY() {
        return getPacket().y;
    }

    public short getW() {
        return getPacket().w;
    }

    public short getH() {
        return getPacket().h;
    }

    public String getOwnerUsername() {
        return getPacket().ownerUsername;
    }

    public @Nullable String getOwner() {
        return (String) getField("owner");
    }

    public @Nullable String getInvited() {
        return (String) getField("invited");
    }
}
