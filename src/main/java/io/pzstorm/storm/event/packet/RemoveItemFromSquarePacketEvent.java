package io.pzstorm.storm.event.packet;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Field;
import javax.annotation.Nullable;
import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RemoveItemFromSquarePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.RemoveItemFromSquarePacket} is
 * processed on the server.
 */
public class RemoveItemFromSquarePacketEvent extends PacketEvent {

    public RemoveItemFromSquarePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RemoveItemFromSquarePacket getPacket() {
        return (RemoveItemFromSquarePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RemoveItemFromSquarePacketEvent";
    }

    public int getX() {
        return getPacket().x;
    }

    public int getY() {
        return getPacket().y;
    }

    public @Nullable Byte getZ() {
        try {
            Field zField = RemoveItemFromSquarePacket.class.getDeclaredField("z");
            zField.setAccessible(true);
            return zField.getByte(getPacket());
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Unable to access z", e);
            return null;
        }
    }

    public short getIndex() {
        return getPacket().index;
    }
}
