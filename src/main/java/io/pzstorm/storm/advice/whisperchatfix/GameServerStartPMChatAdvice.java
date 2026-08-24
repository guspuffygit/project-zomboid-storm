package io.pzstorm.storm.advice.whisperchatfix;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import zombie.characters.IsoPlayer;
import zombie.chat.ChatBase;
import zombie.chat.ChatTab;
import zombie.chat.ChatUtility;
import zombie.chat.defaultChats.WhisperChat;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.chat.ChatServer;
import zombie.network.chat.ChatType;

/**
 * Replaces {@code GameServer.receivePlayerStartPMChat} with a corrected whisper-chat creation flow.
 * Vanilla delegates to {@code ChatServer.processPlayerStartWhisperChatPacket}, which trusts two
 * client-supplied strings and fails in three distinct ways:
 *
 * <ol>
 *   <li><b>The client-supplied author name can be garbage.</b> {@code ChatManager.myNickname} is
 *       cached from {@code IsoPlayer.username} when the {@code InitPlayerChat} packet arrives; if
 *       that races ahead of {@code IsoWorld} assigning {@code GameClient.username}, the nickname is
 *       the hardcoded default {@code "Bob"} for the whole session. Vanilla then can't resolve the
 *       author, throws {@code RuntimeException("Player not found")} without replying, and the
 *       author client silently times out on <i>every</i> whisper it initiates (25% of all whisper
 *       attempts in the 2026-08-24 ATF sample). The connection that delivered the packet identifies
 *       the author authoritatively — resolve from {@link UdpConnection#players} and ignore the
 *       client-supplied name.
 *   <li><b>Server-side destination lookup is case-sensitive.</b> Vanilla {@code
 *       ChatUtility.findPlayer} uses {@code String.equals}, so {@code /whisper bob hi} fails when
 *       the player is registered as {@code Bob}. Use {@link
 *       GameServer#getPlayerByUserNameForCommand} (case-insensitive), falling back to {@link
 *       GameServer#getPlayerByUserName} to keep vanilla's display-name matching.
 *   <li><b>Dest client silently bails on case-mismatch.</b> Client-side {@code WhisperChat.init()}
 *       compares {@code player1}/{@code player2} against its own username with {@code .equals}, so
 *       the chat must be constructed with the players' <i>canonical</i> usernames, not the as-typed
 *       names.
 * </ol>
 */
public class GameServerStartPMChatAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.Argument(0) ByteBufferReader bb, @Advice.Argument(1) UdpConnection connection) {
        return GameServerStartPMChatAdvice.run(bb, connection);
    }

    public static boolean run(ByteBufferReader bb, UdpConnection connection) {
        try {
            if (!ChatUtility.chatStreamEnabled(ChatType.whisper)) {
                LOGGER.info(
                        "WhisperFix: whisper chat is disabled by server settings — packet ignored");
                return true;
            }

            String authorName = bb.getUTF();
            String destPlayerName = bb.getUTF();
            LOGGER.info(
                    "WhisperFix: player '{}' attempts to start whisper with '{}'",
                    authorName,
                    destPlayerName);

            IsoPlayer player1 = resolveAuthor(connection, authorName);
            if (player1 == null) {
                LOGGER.error(
                        "WhisperFix: connection '{}' has no players — cannot resolve author '{}',"
                                + " packet dropped",
                        connection.getUserName(),
                        authorName);
                return true;
            }

            IsoPlayer player2 = GameServer.getPlayerByUserNameForCommand(destPlayerName);
            if (player2 == null) {
                player2 = GameServer.getPlayerByUserName(destPlayerName);
            }
            if (player2 == null) {
                LOGGER.info(
                        "WhisperFix: destination '{}' not found — sending PlayerNotFound to '{}'",
                        destPlayerName,
                        player1.getUsername());
                sendPlayerNotFound(connection, destPlayerName);
                return true;
            }

            String canonicalAuthor = player1.getUsername();
            String canonicalDest = player2.getUsername();

            ChatTab mainTab = getMainTab();
            if (mainTab == null) {
                LOGGER.error("WhisperFix: ChatServer 'main' tab missing — aborting");
                return true;
            }

            ChatServer cs = ChatServer.getInstance();
            int chatId = nextChatId(cs);
            WhisperChat chat = new WhisperChat(chatId, mainTab, canonicalAuthor, canonicalDest);
            chat.addMember(player1.getOnlineID());
            chat.addMember(player2.getOnlineID());
            registerChat(chat);

            LOGGER.info(
                    "WhisperFix: whisper chat id={} created between '{}' (typed: '{}') and '{}'"
                            + " (typed: '{}')",
                    chatId,
                    canonicalAuthor,
                    authorName,
                    canonicalDest,
                    destPlayerName);
            return true;
        } catch (Throwable t) {
            LOGGER.error("WhisperFix: unexpected failure handling whisper start packet", t);
            return true;
        }
    }

    /**
     * The connection that delivered the packet owns the author. Prefer the connection player whose
     * username matches the client-supplied name (splitscreen), otherwise take the first player on
     * the connection — the client-supplied name may be the stale {@code "Bob"} default.
     */
    public static IsoPlayer resolveAuthor(UdpConnection connection, String authorName) {
        IsoPlayer fallback = null;
        for (IsoPlayer player : connection.players) {
            if (player == null) {
                continue;
            }
            if (fallback == null) {
                fallback = player;
            }
            if (player.getUsername() != null && player.getUsername().equalsIgnoreCase(authorName)) {
                return player;
            }
        }
        if (fallback != null) {
            LOGGER.info(
                    "WhisperFix: client-supplied author '{}' does not match any player on the"
                            + " connection — using '{}'",
                    authorName,
                    fallback.getUsername());
        }
        return fallback;
    }

    private static void sendPlayerNotFound(UdpConnection conn, String destPlayerName) {
        ByteBufferWriter b = conn.startPacket();
        PacketTypes.PacketType.PlayerNotFound.doPacket(b);
        b.putUTF(destPlayerName);
        PacketTypes.PacketType.PlayerNotFound.send(conn);
    }

    private static ChatTab getMainTab() throws ReflectiveOperationException {
        Field f = ChatServer.class.getDeclaredField("tabs");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ChatTab> tabs = (Map<String, ChatTab>) f.get(null);
        return tabs.get("main");
    }

    private static int nextChatId(ChatServer cs) throws ReflectiveOperationException {
        Method m = ChatServer.class.getDeclaredMethod("getNextChatID");
        m.setAccessible(true);
        return (int) m.invoke(cs);
    }

    private static void registerChat(ChatBase chat) throws ReflectiveOperationException {
        Field f = ChatServer.class.getDeclaredField("chats");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, ChatBase> chats = (Map<Integer, ChatBase>) f.get(null);
        chats.put(chat.getID(), chat);
    }
}
