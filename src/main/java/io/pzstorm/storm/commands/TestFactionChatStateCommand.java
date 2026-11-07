package io.pzstorm.storm.commands;

import io.pzstorm.storm.advice.factionchat.FactionChatRekey;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import zombie.characters.Capability;
import zombie.characters.Faction;
import zombie.characters.Role;
import zombie.chat.ChatBase;
import zombie.chat.defaultChats.FactionChat;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.raknet.UdpConnection;

/**
 * Reports whether a faction and a faction chat exist under a name, and the chat's member count.
 * Used by {@code io.pzstorm.storm.liveserver.FactionChatRenameLiveTest}.
 *
 * <p>Usage: {@code stormtestfactionchatstate <name>}.
 */
@CommandName(name = "stormtestfactionchatstate")
@CommandHelp(
        helpText =
                "Reports faction and faction-chat state for a name: stormtestfactionchatstate"
                        + " <name>",
        shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DebugConsole)
public class TestFactionChatStateCommand extends CommandBase {

    public TestFactionChatStateCommand(
            String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    public String Execute() throws SQLException {
        return Command();
    }

    @Override
    protected String Command() {
        String name = getCommandArg(0);
        if (name == null || name.isEmpty()) {
            return "RESULT ERROR usage: stormtestfactionchatstate <name>";
        }
        try {
            Map<String, FactionChat> chats = FactionChatRekey.factionChats();
            FactionChat chat = chats.get(name);
            int members = -1;
            if (chat != null) {
                Field membersField = ChatBase.class.getDeclaredField("members");
                membersField.setAccessible(true);
                members = ((List<?>) membersField.get(chat)).size();
            }
            return "RESULT FACTION name="
                    + name
                    + " faction="
                    + Faction.factionExist(name)
                    + " chat="
                    + (chat != null)
                    + " members="
                    + members;
        } catch (Exception e) {
            return "RESULT ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
