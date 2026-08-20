package io.pzstorm.storm.connection;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side record of which RakNet connections run Storm and at what version, keyed by connection
 * guid. Fed by the {@code StormPlayers.hello} client command that Storm's client Lua sends once it
 * is in-game; {@link StormTcpSessionRegistry} is consulted as a fallback for clients whose hello
 * has not arrived (or whose Lua never ran) but that completed the game-port TCP handshake.
 *
 * <p>Keyed by connection rather than username so a respawn (new IsoPlayer, same connection) keeps
 * the record and a reconnect (new connection) starts clean. Dead guids are swept against the live
 * connection table on every request and whenever the map grows past {@link #SWEEP_THRESHOLD}.
 */
public final class StormClientVersionRegistry {

    /** Longest version string accepted from a client; anything longer is truncated. */
    static final int MAX_VERSION_LENGTH = 32;

    /** Map size past which a hello triggers a sweep of dead connections. */
    static final int SWEEP_THRESHOLD = 1024;

    private static final Map<Long, String> BY_GUID = new ConcurrentHashMap<>();

    private StormClientVersionRegistry() {}

    /**
     * Record the Storm version announced by the client on {@code guid}.
     *
     * @return the stored (sanitized) version, or {@code null} if the announcement was unusable.
     */
    public static @Nullable String record(long guid, @Nullable String version) {
        String clean = sanitize(version);
        if (clean == null) {
            return null;
        }
        BY_GUID.put(guid, clean);
        return clean;
    }

    public static @Nullable String versionOf(long guid) {
        return BY_GUID.get(guid);
    }

    public static int size() {
        return BY_GUID.size();
    }

    public static boolean needsSweep() {
        return BY_GUID.size() > SWEEP_THRESHOLD;
    }

    /** Drop every entry whose guid {@code alive} rejects. Returns the number dropped. */
    public static int sweep(LongPredicate alive) {
        int dropped = 0;
        for (Iterator<Long> it = BY_GUID.keySet().iterator(); it.hasNext(); ) {
            if (!alive.test(it.next())) {
                it.remove();
                dropped++;
            }
        }
        return dropped;
    }

    public static void reset() {
        BY_GUID.clear();
    }

    static @Nullable String sanitize(@Nullable String version) {
        if (version == null) {
            return null;
        }
        String trimmed = version.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_VERSION_LENGTH
                ? trimmed.substring(0, MAX_VERSION_LENGTH)
                : trimmed;
    }
}
