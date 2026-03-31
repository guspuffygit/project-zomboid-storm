package io.pzstorm.storm.event.packet;

import java.util.ArrayList;
import javax.annotation.Nullable;
import zombie.core.raknet.UdpConnection;
import zombie.inventory.InventoryItem;
import zombie.network.fields.ContainerID;
import zombie.network.packets.AddInventoryItemToContainerPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.AddInventoryItemToContainerPacket} is
 * processed on the server.
 */
public class AddInventoryItemToContainerPacketEvent extends PacketEvent {

    public AddInventoryItemToContainerPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AddInventoryItemToContainerPacket getPacket() {
        return (AddInventoryItemToContainerPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AddInventoryItemToContainerPacketEvent";
    }

    public @Nullable ContainerID getContainerId() {
        return (ContainerID) getField("containerId");
    }

    @SuppressWarnings("unchecked")
    public @Nullable ArrayList<InventoryItem> getItems() {
        return (ArrayList<InventoryItem>) getField("items");
    }

    public int getX() {
        return getPacket().getX();
    }

    public int getY() {
        return getPacket().getY();
    }
}
