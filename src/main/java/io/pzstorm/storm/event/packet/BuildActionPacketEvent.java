package io.pzstorm.storm.event.packet;

import io.pzstorm.storm.lua.StormKahluaTable;
import javax.annotation.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoGridSquare;
import zombie.network.packets.BuildActionPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.BuildActionPacket} is processed on the
 * server.
 */
public class BuildActionPacketEvent extends PacketEvent {

    public BuildActionPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public BuildActionPacket getPacket() {
        return (BuildActionPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "BuildActionPacketEvent";
    }

    public @Nullable Float getX() {
        return (Float) getField("x");
    }

    public @Nullable Float getY() {
        return (Float) getField("y");
    }

    public @Nullable Float getZ() {
        return (Float) getField("z");
    }

    public @Nullable Boolean isNorth() {
        return (Boolean) getField("north");
    }

    public @Nullable String getSpriteName() {
        return (String) getField("spriteName");
    }

    public @Nullable StormKahluaTable getItem() {
        KahluaTable item = getPacket().item;
        if (item == null) {
            return null;
        }

        return new StormKahluaTable(item);
    }

    public @Nullable String getObjectType() {
        return (String) getField("objectType");
    }

    public @Nullable IsoGridSquare getSquare() {
        return (IsoGridSquare) getField("square");
    }

    public @Nullable StormKahluaTable getArgTable() {
        KahluaTable argTable = (KahluaTable) getField("argTable");
        if (argTable == null) {
            return null;
        }

        return new StormKahluaTable(argTable);
    }
}
