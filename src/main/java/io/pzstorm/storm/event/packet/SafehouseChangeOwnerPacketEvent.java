package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.iso.areas.SafeHouse;
import zombie.network.packets.safehouse.SafehouseChangeOwnerPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafehouseChangeOwnerPacket}
 * is processed on the server.
 */
public class SafehouseChangeOwnerPacketEvent extends PacketEvent {

    private String previousOwner;

    public SafehouseChangeOwnerPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    @Override
    public void capturePreState() {
        SafeHouse safehouse = getSafehouse();
        if (safehouse != null) {
            previousOwner = safehouse.getOwner();
        }
    }

    public SafehouseChangeOwnerPacket getPacket() {
        return (SafehouseChangeOwnerPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SafehouseChangeOwnerPacketEvent";
    }

    public SafeHouse getSafehouse() {
        return getPacket().getSafehouse();
    }

    public String getPlayer() {
        return getPacket().player;
    }

    /** Returns the safehouse owner before the ownership change was processed. */
    public String getPreviousOwner() {
        return previousOwner;
    }
}
