package io.pzstorm.storm.event.packet;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Field;
import javax.annotation.Nullable;
import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RemoveItemFromSquarePacket;
import zombie.network.packets.SledgehammerDestroyPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.SledgehammerDestroyPacket} is processed
 * on the server.
 */
public class SledgehammerDestroyPacketEvent extends PacketEvent {

    public SledgehammerDestroyPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SledgehammerDestroyPacket getPacket() {
        return (SledgehammerDestroyPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SledgehammerDestroyPacketEvent";
    }

    public @Nullable RemoveItemFromSquarePacket getInnerPacket() {
        return (RemoveItemFromSquarePacket) getField("packet");
    }

    public @Nullable Integer getX() {
        RemoveItemFromSquarePacket inner = getInnerPacket();
        if (inner == null) {
            return null;
        }
        return inner.x;
    }

    public @Nullable Integer getY() {
        RemoveItemFromSquarePacket inner = getInnerPacket();
        if (inner == null) {
            return null;
        }
        return inner.y;
    }

    public @Nullable Byte getZ() {
        RemoveItemFromSquarePacket inner = getInnerPacket();
        if (inner == null) {
            return null;
        }
        try {
            Field zField = RemoveItemFromSquarePacket.class.getDeclaredField("z");
            zField.setAccessible(true);
            return zField.getByte(inner);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Unable to access z", e);
            return null;
        }
    }

    public @Nullable Short getIndex() {
        RemoveItemFromSquarePacket inner = getInnerPacket();
        if (inner == null) {
            return null;
        }
        return inner.index;
    }
}
