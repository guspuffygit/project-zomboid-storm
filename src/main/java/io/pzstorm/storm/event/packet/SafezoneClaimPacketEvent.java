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
        Integer v = (Integer) getField("x");
        return v != null ? v : 0;
    }

    public int getY() {
        Integer v = (Integer) getField("y");
        return v != null ? v : 0;
    }

    public int getW() {
        Integer v = (Integer) getField("w");
        return v != null ? v : 0;
    }

    public int getH() {
        Integer v = (Integer) getField("h");
        return v != null ? v : 0;
    }

    public @Nullable String getTitle() {
        return (String) getField("title");
    }
}
