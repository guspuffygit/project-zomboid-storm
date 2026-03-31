package io.pzstorm.storm.event.packet;

import javax.annotation.Nullable;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoObject;
import zombie.network.packets.AddItemToMapPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.AddItemToMapPacket} is processed on the
 * server.
 */
public class AddItemToMapPacketEvent extends PacketEvent {

    public AddItemToMapPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AddItemToMapPacket getPacket() {
        return (AddItemToMapPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AddItemToMapPacketEvent";
    }

    public @Nullable IsoObject getIsoObject() {
        return (IsoObject) getField("obj");
    }
}
