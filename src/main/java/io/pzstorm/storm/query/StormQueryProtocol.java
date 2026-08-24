package io.pzstorm.storm.query;

/**
 * Wire constants for Storm's pre-login server query — the packet pair the Storm Launcher uses to
 * learn a server's required workshop items <i>before</i> the game process starts.
 *
 * <p>The query rides PZ's own user-packet framing ({@code byte 134}, {@code short packetId},
 * payload) on an established RakNet connection, but uses packet ids far outside the vanilla enum
 * ordinal range (vanilla ends at 294). Vanilla resolves ids through {@code
 * PacketTypes.packetTypes.get(id)} and treats a miss as a logged drop, so a server without Storm —
 * or with an older Storm — simply ignores the query and the launcher times out and degrades to its
 * previous behavior.
 *
 * <p>Answering happens in {@code GameServer.addIncoming}, on the UdpEngine thread, which runs
 * <i>before</i> the {@code mainLoopDealWithNetData} login gate that force-disconnects any
 * non-allowlisted packet from a connection with no username. That is what makes this work without
 * credentials.
 *
 * <p>This class is duplicated by {@code io.pzstorm.launcher.net.StormQueryProtocol} in the launcher
 * subproject, which cannot depend on Storm or PZ. Change both together.
 */
public final class StormQueryProtocol {

    /** PZ user-packet framing: the RakNet message id that prefixes every PZ packet. */
    public static final int RAKNET_USER_PACKET_ENUM = 134;

    /**
     * Request id. Chosen in the 0x7A00 block: vanilla ordinals end at 294 and grow upward with each
     * update, so this leaves ~28k of headroom before a collision is even conceivable.
     */
    public static final short QUERY_PACKET_ID = 0x7A17;

    /** Response id. */
    public static final short QUERY_REPLY_PACKET_ID = 0x7A18;

    /** Leading payload magic ({@code "STMQ"}), so a stray packet at this id is not misparsed. */
    public static final int MAGIC = 0x53544D51;

    /**
     * Bumped only for incompatible payload changes; the responder answers older versions too.
     *
     * <p>Version 2 appends the server's three join-checksum totals (Lua, scripts, animations) after
     * the mod list. A v1 reader simply never reaches the trailing fields, and a v2 reader gates on
     * the version it finds in the reply, so both directions stay compatible.
     */
    public static final int PROTOCOL_VERSION = 2;

    /**
     * Hard ceiling on a reply payload. RakNet fragments anything past the negotiated MTU (~446 B on
     * a stock server) and the launcher reassembles, but an unbounded reply would let a huge mod
     * list turn one query into a multi-megabyte send.
     */
    public static final int MAX_REPLY_BYTES = 512 * 1024;

    /**
     * Replies allowed per connection. The query needs a completed RakNet handshake, so a spoofed
     * source address cannot trigger a reply at all (no UDP amplification); this only bounds a
     * genuine peer re-asking in a loop.
     */
    public static final int MAX_REPLIES_PER_CONNECTION = 8;

    private StormQueryProtocol() {
        throw new AssertionError("Utility class");
    }
}
