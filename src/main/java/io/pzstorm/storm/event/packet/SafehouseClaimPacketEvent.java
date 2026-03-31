package io.pzstorm.storm.event.packet;

import javax.annotation.Nullable;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.network.fields.Square;
import zombie.network.packets.safehouse.SafehouseClaimPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafehouseClaimPacket} is
 * processed on the server.
 */
public class SafehouseClaimPacketEvent extends PacketEvent {

    public SafehouseClaimPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafehouseClaimPacket getPacket() {
        return (SafehouseClaimPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SafehouseClaimPacketEvent";
    }

    public IsoPlayer getPlayer() {
        return getPacket().getPlayer();
    }

    public @Nullable String getTitle() {
        return (String) getField("title");
    }

    public @Nullable Square getSquare() {
        return (Square) getField("square");
    }
}
