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
 * Replaces {@code ChatServer.processPlayerStartWhisperChatPacket} with a corrected implementation
 * that fixes two case-sensitivity bugs that prevent whisper chats from ever reaching the
 * destination client:
 *
 * <ol>
 *   <li><b>Server-side lookup is case-sensitive.</b> Vanilla {@code GameServer.getPlayerByUserName}
 *       (reached via {@code ChatUtility.findPlayer}) uses {@code String.equals}. Typing {@code
 *       /whisper bob hi} when the player's actual username is {@code Bob} resolves to {@code null}
 *       and the server replies with {@code PlayerNotFound}.
 *   <li><b>Dest client silently bails on case-mismatch.</b> Vanilla {@code WhisperChat.init()}
 *       (client-side) checks {@code player1.equals(IsoPlayer.getInstance().getUsername())} and
 *       {@code player2.equals(IsoPlayer.getInstance().getUsername())} with {@code .equals}. If the
 *       server packs the chat with the as-typed name (which has wrong case), neither branch matches
 *       on the destination client, {@code init()} returns without setting {@code companionName},
 *       and the chat is broken.
 * </ol>
 *
 * <p>Fix: look up both author and destination via {@link GameServer#getPlayerByUserNameForCommand}
 * (case-insensitive), then construct the {@link WhisperChat} with the players' <i>canonical</i>
 * usernames so the dest-client {@code init()} matches regardless of how the sender typed the name.
 * The HARD RULE forbids client-side patches, so the fix lives entirely in the server-only {@code
 * ChatServer} class.
 */
public class ChatServerWhisperAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.This Object self, @Advice.Argument(0) ByteBufferReader bb) {
        return ChatServerWhisperAdvice.run((ChatServer) self, bb);
    }

    public static boolean run(ChatServer cs, ByteBufferReader bb) {
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

            IsoPlayer player1 = GameServer.getPlayerByUserNameForCommand(authorName);
            IsoPlayer player2 = GameServer.getPlayerByUserNameForCommand(destPlayerName);

            if (player1 == null) {
                LOGGER.error("WhisperFix: author '{}' not found on server", authorName);
                throw new RuntimeException("Player not found");
            }
            if (player2 == null) {
                LOGGER.info(
                        "WhisperFix: destination '{}' not found — sending PlayerNotFound to '{}'",
                        destPlayerName,
                        authorName);
                UdpConnection conn = ChatUtility.findConnection(player1.getOnlineID());
                if (conn != null) {
                    sendPlayerNotFound(conn, destPlayerName);
                }
                return true;
            }

            String canonicalAuthor = player1.getUsername();
            String canonicalDest = player2.getUsername();

            ChatTab mainTab = getMainTab();
            if (mainTab == null) {
                LOGGER.error("WhisperFix: ChatServer 'main' tab missing — aborting");
                return true;
            }

            int chatId = nextChatId(cs);
            WhisperChat chat = new WhisperChat(chatId, mainTab, canonicalAuthor, canonicalDest);
            chat.addMember(player1.getOnlineID());
            chat.addMember(player2.getOnlineID());
            registerChat(chat);

            LOGGER.info(
                    "WhisperFix: whisper chat id={} created between '{}' (typed: '{}') and '{}' (typed: '{}')",
                    chatId,
                    canonicalAuthor,
                    authorName,
                    canonicalDest,
                    destPlayerName);
            return true;
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable t) {
            LOGGER.error("WhisperFix: unexpected failure handling whisper start packet", t);
            return true;
        }
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
