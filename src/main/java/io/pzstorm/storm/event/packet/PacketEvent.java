package io.pzstorm.storm.event.packet;

import io.pzstorm.storm.event.core.ZomboidEvent;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import zombie.core.raknet.UdpConnection;

/**
 * Base class for all typed packet events. Each packet class in {@link
 * io.pzstorm.storm.event.core.PacketEventDispatcher#SUPPORTED_PACKETS} has a corresponding subclass
 * that provides a typed {@code getPacket()} method.
 *
 * <p>Handlers use {@link io.pzstorm.storm.event.core.SubscribeEvent @SubscribeEvent} with the
 * specific typed event as the parameter type.
 */
public abstract class PacketEvent implements ZomboidEvent {

    private final Object packet;
    public final String username;
    public final long steamId;
    public final UdpConnection connection;
    private @Nullable Map<String, Object> fieldCache;

    protected PacketEvent(Object packet, UdpConnection connection) {
        this.packet = packet;
        this.username = connection.getUserName();
        this.steamId = connection.getSteamId();
        this.connection = connection;
    }

    /** Returns the raw packet object. Typed subclasses shadow this with a typed return. */
    protected Object getRawPacket() {
        return packet;
    }

    /**
     * Read any field (including inherited and protected) from the packet by name. Results are
     * cached after the first access.
     *
     * @param name the field name (e.g. "state", "sourceId", "itemId")
     * @return the field value, or {@code null} if the field does not exist
     */
    public @Nullable Object getField(String name) {
        if (fieldCache == null) {
            fieldCache = new HashMap<>();
        }
        if (fieldCache.containsKey(name)) {
            return fieldCache.get(name);
        }
        Object value = readField(name);
        fieldCache.put(name, value);
        return value;
    }

    private @Nullable Object readField(String name) {
        Class<?> clazz = packet.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(packet);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }
}
