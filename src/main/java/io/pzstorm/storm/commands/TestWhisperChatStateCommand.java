package io.pzstorm.storm.commands;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.Map;
import zombie.characters.Capability;
import zombie.characters.Role;
import zombie.chat.ChatBase;
import zombie.chat.defaultChats.WhisperChat;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.raknet.UdpConnection;
import zombie.network.chat.ChatServer;

/**
 * Inspects {@link ChatServer#chats} server-side and returns either the count of {@link WhisperChat}
 * instances or the {@code player1}/{@code player2} field values of the most-recently-created one.
 * Used by {@link io.pzstorm.storm.liveserver.WhisperChatCaseInsensitiveLiveTest} to assert that
 * {@link io.pzstorm.storm.advice.whisperchatfix.ChatServerWhisperAdvice} stored canonical (case-
 * correct) usernames after the bug-bare wire packet was processed.
 *
 * <p>Usage: {@code stormtestwhisperchatstate}.
 */
@CommandName(name = "stormtestwhisperchatstate")
@CommandHelp(
        helpText =
                "Reports the count of server WhisperChat instances plus player1/player2 of the"
                        + " most recent one: stormtestwhisperchatstate",
        shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DebugConsole)
public class TestWhisperChatStateCommand extends CommandBase {

    public TestWhisperChatStateCommand(
            String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    public String Execute() throws SQLException {
        return Command();
    }

    @Override
    protected String Command() {
        try {
            Field chatsField = ChatServer.class.getDeclaredField("chats");
            chatsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Integer, ChatBase> chats = (Map<Integer, ChatBase>) chatsField.get(null);

            int whisperCount = 0;
            WhisperChat latest = null;
            int latestId = -1;
            for (Map.Entry<Integer, ChatBase> e : chats.entrySet()) {
                if (e.getValue() instanceof WhisperChat) {
                    whisperCount++;
                    if (e.getKey() > latestId) {
                        latestId = e.getKey();
                        latest = (WhisperChat) e.getValue();
                    }
                }
            }

            if (latest == null) {
                return "RESULT WHISPER count=0 latestId=-1 player1=- player2=-";
            }

            Field p1Field = WhisperChat.class.getDeclaredField("player1");
            Field p2Field = WhisperChat.class.getDeclaredField("player2");
            p1Field.setAccessible(true);
            p2Field.setAccessible(true);
            String player1 = (String) p1Field.get(latest);
            String player2 = (String) p2Field.get(latest);

            return "RESULT WHISPER count="
                    + whisperCount
                    + " latestId="
                    + latestId
                    + " player1="
                    + player1
                    + " player2="
                    + player2;
        } catch (Exception e) {
            return "RESULT ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
