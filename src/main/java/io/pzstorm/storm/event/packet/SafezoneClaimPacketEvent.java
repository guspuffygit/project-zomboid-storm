package io.pzstorm.storm.event.packet;

import javax.annotation.Nullable;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.network.packets.safehouse.SafezoneClaimPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafezoneClaimPacket} is
 * processed on the server.
 */
public class SafezoneClaimPacketEvent extends PacketEvent {

    public SafezoneClaimPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafezoneClaimPacket getPacket() {
        return (SafezoneClaimPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SafezoneClaimPacketEvent";
    }

    public IsoPlayer getPlayer() {
        return getPacket().getPlayer();
    }

    public int getX() {
        return getPacket().x;
    }

    public int getY() {
        return getPacket().y;
    }

    public int getW() {
        return getPacket().w;
    }

    public int getH() {
        return getPacket().h;
    }

    public @Nullable String getTitle() {
        return (String) getField("title");
    }
}
