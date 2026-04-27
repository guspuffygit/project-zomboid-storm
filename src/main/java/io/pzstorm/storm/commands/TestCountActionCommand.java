package io.pzstorm.storm.commands;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentLinkedQueue;
import zombie.characters.Capability;
import zombie.characters.Role;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.ActionManager;
import zombie.core.NetTimedAction;
import zombie.core.raknet.UdpConnection;

@CommandName(name = "stormtestcountaction")
@CommandHelp(
        helpText =
                "Returns the number of queued actions on the server with the given byte id:"
                        + " stormtestcountaction <byteId>",
        shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DebugConsole)
public class TestCountActionCommand extends CommandBase {

    public TestCountActionCommand(
            String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    public String Execute() throws SQLException {
        return Command();
    }

    @Override
    protected String Command() {
        String byteIdStr = getCommandArg(0);
        if (byteIdStr == null) {
            return "RESULT ERROR Usage: stormtestcountaction <byteId>";
        }
        try {
            byte targetId = Byte.parseByte(byteIdStr);
            int count = countActionsWithId(targetId);
            return "RESULT COUNT id=" + targetId + " count=" + count;
        } catch (Exception e) {
            return "RESULT ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private static int countActionsWithId(byte id) throws Exception {
        Field actionsField = ActionManager.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        ConcurrentLinkedQueue<?> queue = (ConcurrentLinkedQueue<?>) actionsField.get(null);
        Field idField = getActionClass().getDeclaredField("id");
        idField.setAccessible(true);
        int count = 0;
        for (Object obj : queue) {
            if (idField.getByte(obj) == id) {
                count++;
            }
        }
        return count;
    }

    private static Class<?> getActionClass() {
        return NetTimedAction.class.getSuperclass();
    }
}
