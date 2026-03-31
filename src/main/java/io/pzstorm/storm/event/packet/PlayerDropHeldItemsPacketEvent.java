package io.pzstorm.storm.event.packet;

import javax.annotation.Nullable;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.PlayerDropHeldItemsPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.character.PlayerDropHeldItemsPacket} is
 * processed on the server.
 */
public class PlayerDropHeldItemsPacketEvent extends PacketEvent {

    public PlayerDropHeldItemsPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public PlayerDropHeldItemsPacket getPacket() {
        return (PlayerDropHeldItemsPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "PlayerDropHeldItemsPacketEvent";
    }

    public IsoPlayer getPlayer() {
        return getPacket().getPlayer();
    }

    public @Nullable Boolean isHeavy() {
        return (Boolean) getField("heavy");
    }

    public @Nullable Boolean isThrow() {
        return (Boolean) getField("isThrow");
    }

    public @Nullable Integer getX() {
        return (Integer) getField("x");
    }

    public @Nullable Integer getY() {
        return (Integer) getField("y");
    }

    public @Nullable Integer getZ() {
        return (Integer) getField("z");
    }
}
