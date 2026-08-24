package io.pzstorm.storm.advice.whisperchatfix;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import zombie.chat.ChatBase;
import zombie.network.chat.ChatServer;
import zombie.network.chat.ChatType;

/**
 * Replaces {@code ChatServer.disconnectPlayer(short)} to close only the whisper chats the leaving
 * player was actually a member of. The vanilla loop closes <b>every</b> {@code ChatType.whisper}
 * chat server-wide on <i>any</i> disconnect:
 *
 * <pre>{@code
 * for (ChatBase chat : chats.values()) {
 *    chat.removeMember(playerID);
 *    if (chat.getType() == ChatType.whisper) {
 *       this.closeChat(chat.getID());   // unconditional — not scoped to the leaver's chats
 *    }
 * }
 * }</pre>
 *
 * <p>On a populated server a disconnect happens every few minutes, so no whisper chat survives
 * (observed on ATF 2026-08-24: 54 whisper chats created in an hour, 0 alive at 118 players). Every
 * PM conversation is silently torn down and both parties must re-initiate, which multiplies traffic
 * through the whisper-creation path that {@link GameServerStartPMChatAdvice} fixes.
 *
 * <p>The replacement mirrors vanilla's lock scopes ({@code synchronized} on the {@code chats} and
 * {@code players} monitors) and close semantics ({@code closeChat} notifies the surviving member
 * and recycles the chat id) — the only change is the membership check before closing.
 */
public class ChatServerDisconnectAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object self, @Advice.Argument(0) short playerID) {
        return ChatServerDisconnectAdvice.run((ChatServer) self, playerID);
    }

    public static boolean run(ChatServer cs, short playerID) {
        Map<Integer, ChatBase> chats;
        Set<Short> players;
        Method hasMember;
        Method closeChat;
        try {
            chats = getStaticMap("chats");
            players = getStaticSet();
            hasMember = ChatBase.class.getDeclaredMethod("hasMember", Short.class);
            hasMember.setAccessible(true);
            closeChat = ChatServer.class.getDeclaredMethod("closeChat", int.class);
            closeChat.setAccessible(true);
        } catch (Throwable t) {
            LOGGER.error(
                    "WhisperFix: disconnectPlayer reflection failed — falling back to vanilla", t);
            return false;
        }

        try {
            List<Integer> whispersToClose = new ArrayList<>();
            synchronized (chats) {
                for (ChatBase chat : chats.values()) {
                    boolean wasMember = (boolean) hasMember.invoke(chat, playerID);
                    chat.removeMember(playerID);
                    if (wasMember && chat.getType() == ChatType.whisper) {
                        whispersToClose.add(chat.getID());
                    }
                }
                for (Integer chatId : whispersToClose) {
                    closeChat.invoke(cs, chatId);
                }
            }
            synchronized (players) {
                players.remove(playerID);
            }
            LOGGER.info(
                    "WhisperFix: player {} disconnected — closed {} whisper chat(s) they were a"
                            + " member of",
                    playerID,
                    whispersToClose.size());
        } catch (Throwable t) {
            LOGGER.error("WhisperFix: disconnectPlayer({}) failed", playerID, t);
        }
        return true;
    }

    private static Map<Integer, ChatBase> getStaticMap(String name)
            throws ReflectiveOperationException {
        Field f = ChatServer.class.getDeclaredField(name);
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, ChatBase> value = (Map<Integer, ChatBase>) f.get(null);
        return value;
    }

    private static Set<Short> getStaticSet() throws ReflectiveOperationException {
        Field f = ChatServer.class.getDeclaredField("players");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Short> value = (Set<Short>) f.get(null);
        return value;
    }
}
