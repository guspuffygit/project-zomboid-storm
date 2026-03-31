package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.iso.areas.SafeHouse;
import zombie.network.packets.safehouse.SafehouseChangeRespawnPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafehouseChangeRespawnPacket}
 * is processed on the server.
 */
public class SafehouseChangeRespawnPacketEvent extends PacketEvent {

    private boolean wasRespawning;

    public SafehouseChangeRespawnPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafehouseChangeRespawnPacket getPacket() {
        return (SafehouseChangeRespawnPacket) getRawPacket();
    }

    @Override
    public void capturePreState() {
        SafeHouse safehouse = getSafehouse();
        if (safehouse != null) {
            wasRespawning = safehouse.isRespawnInSafehouse(getPlayer());
        }
    }

    public boolean wasRespawning() {
        return wasRespawning;
    }

    /**
     * Returns whether this packet will add the player to the respawn list. Note: the underlying
     * packet field {@code doRemove} is misnamed — {@code true} means the player is being added, not
     * removed.
     */
    public boolean isAddingRespawn() {
        return getPacket().doRemove;
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
