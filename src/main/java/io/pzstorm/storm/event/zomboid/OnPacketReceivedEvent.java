package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import zombie.core.raknet.UdpConnection;

/**
 * Dispatched on the server whenever a patched packet's {@code processServer} method is called.
 *
 * <p>Use {@link io.pzstorm.storm.event.core.SubscribeEvent @SubscribeEvent} to receive all packet
 * events, or {@link io.pzstorm.storm.event.core.OnPacketReceived @OnPacketReceived("ClassName")} to
 * filter for a specific packet type.
 *
 * <p>Since most packet fields are {@code protected}, use {@link #getField(String)} to access them
 * via reflection. Results are cached after the first access.
 */
public class OnPacketReceivedEvent implements ZomboidEvent {

    public final String packetClassName;
    public final String username;
    public final long steamId;
    public final UdpConnection connection;
    private final Object packet;
    private @Nullable Map<String, Object> fieldCache;

    public OnPacketReceivedEvent(Object packet, UdpConnection connection) {
        this.packet = packet;
        this.packetClassName = packet.getClass().getSimpleName();
        this.username = connection.getUserName();
        this.steamId = connection.getSteamId();
        this.connection = connection;
    }

    /** Returns the raw packet object. Cast to the specific packet type for direct access. */
    public Object getPacket() {
        return packet;
    }

    /**
     * Read any field (including inherited and protected) from the packet by name. Results are
     * cached after the first call.
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

    @Override
    public String getName() {
        return "OnPacketReceived:" + packetClassName;
    }
}
