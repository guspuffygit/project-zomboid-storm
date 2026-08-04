package io.pzstorm.storm.query;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import zombie.core.Core;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;
import zombie.network.ServerOptions;

/**
 * Answers the Storm Launcher's pre-login server query (see {@link StormQueryProtocol}).
 *
 * <p>Runs on the UdpEngine thread from {@code GameServer.addIncoming}, before vanilla resolves the
 * packet id — so the reply goes out without the querying peer ever logging in. Everything published
 * here is already visible to anyone who can join: the workshop item ids and mod ids the server
 * demands, its name, and version strings.
 *
 * <p>Failure is always soft. Any throwable is logged and swallowed, and the packet is consumed
 * either way so a malformed query can never reach vanilla's unknown-id path.
 */
public final class StormQueryResponder {

    /** Replies served per RakNet peer, keyed by connection guid. */
    private static final Map<Long, Integer> REPLY_COUNTS = new ConcurrentHashMap<>();

    /** Bound on {@link #REPLY_COUNTS} so churn through pre-login peers cannot grow it forever. */
    private static final int MAX_TRACKED_CONNECTIONS = 512;

    private StormQueryResponder() {
        throw new AssertionError("Utility class");
    }

    /**
     * @return {@code true} when the packet was ours and vanilla must not see it.
     */
    public static boolean handle(short id, ByteBufferReader bb, UdpConnection connection) {
        if (id != StormQueryProtocol.QUERY_PACKET_ID) {
            return false;
        }
        try {
            respond(bb, connection);
        } catch (Throwable t) {
            LOGGER.warn("Storm server query failed: {}", t.toString());
        }
        return true;
    }

    private static void respond(ByteBufferReader bb, UdpConnection connection) {
        if (connection == null) {
            return;
        }
        if (bb.bb.remaining() < 8) {
            LOGGER.debug("Storm server query: payload too short, ignoring");
            return;
        }
        int magic = bb.getInt();
        if (magic != StormQueryProtocol.MAGIC) {
            LOGGER.debug("Storm server query: bad magic {}, ignoring", Integer.toHexString(magic));
            return;
        }
        int requestedVersion = bb.getInt();

        long guid = connection.getConnectedGUID();
        if (!allowReply(guid)) {
            LOGGER.warn("Storm server query: reply cap reached for guid {}, ignoring", guid);
            return;
        }

        List<String> workshopItems = workshopItems();
        List<String> mods = serverMods();

        ByteBufferWriter writer = connection.startPacket();
        boolean sent = false;
        try {
            writer.putByte(StormQueryProtocol.RAKNET_USER_PACKET_ENUM);
            writer.putShort(StormQueryProtocol.QUERY_REPLY_PACKET_ID);
            writer.putInt(StormQueryProtocol.MAGIC);
            writer.putInt(StormQueryProtocol.PROTOCOL_VERSION);
            writer.putUTF(StormVersion.getVersion());
            writer.putUTF(gameVersion());
            writer.putUTF(serverName());
            writer.putInt(ServerOptions.getInstance().getMaxPlayers());
            writer.putInt(GameServer.Players.size());
            putStrings(writer, workshopItems);
            putStrings(writer, mods);

            int size = writer.position();
            if (size > StormQueryProtocol.MAX_REPLY_BYTES) {
                LOGGER.warn(
                        "Storm server query: reply of {} bytes exceeds the {} byte cap — not"
                                + " sending. The launcher will fall back to its other sources.",
                        size,
                        StormQueryProtocol.MAX_REPLY_BYTES);
                return;
            }
            connection.endPacketImmediate();
            sent = true;
        } finally {
            // startPacket() took the connection's bufferLock; only endPacketImmediate() or
            // cancelPacket() release it. Leaking it deadlocks every later send on this peer.
            if (!sent) {
                connection.cancelPacket();
            }
        }
        LOGGER.info(
                "Storm server query answered for guid {} (protocol {}): {} workshop item(s), {}"
                        + " mod id(s)",
                guid,
                requestedVersion,
                workshopItems.size(),
                mods.size());
    }

    private static boolean allowReply(long guid) {
        if (REPLY_COUNTS.size() > MAX_TRACKED_CONNECTIONS) {
            REPLY_COUNTS.clear();
        }
        int served = REPLY_COUNTS.merge(guid, 1, Integer::sum);
        return served <= StormQueryProtocol.MAX_REPLIES_PER_CONNECTION;
    }

    /** Called when a peer disconnects so its counter does not linger. */
    public static void forgetConnection(long guid) {
        REPLY_COUNTS.remove(guid);
    }

    private static void putStrings(ByteBufferWriter writer, List<String> values) {
        writer.putInt(values.size());
        for (int i = 0; i < values.size(); i++) {
            writer.putUTF(values.get(i));
        }
    }

    private static List<String> workshopItems() {
        List<String> ids = new ArrayList<>();
        try {
            for (Long itemId : GameServer.WorkshopItems) {
                ids.add(String.valueOf(itemId));
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not read GameServer.WorkshopItems: {}", t.toString());
        }
        return ids;
    }

    private static List<String> serverMods() {
        List<String> mods = new ArrayList<>();
        try {
            mods.addAll(GameServer.ServerMods);
        } catch (Throwable t) {
            LOGGER.warn("Could not read GameServer.ServerMods: {}", t.toString());
        }
        return mods;
    }

    private static String gameVersion() {
        try {
            return Core.getInstance().getVersionNumber();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String serverName() {
        try {
            return ServerOptions.instance.publicName.getValue();
        } catch (Throwable t) {
            return "";
        }
    }
}
