package io.pzstorm.storm.commands;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import se.krka.kahlua.j2se.KahluaTableImpl;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.ActionManager;
import zombie.core.NetTimedAction;
import zombie.core.Transaction;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;

@CommandName(name = "stormtestseedaction")
@CommandHelp(
        helpText =
                "Seeds a fake NetTimedAction with a known byte id into the server's"
                        + " ActionManager queue for the named player:"
                        + " stormtestseedaction <username> <byteId>",
        shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DebugConsole)
public class TestSeedActionCommand extends CommandBase {

    public TestSeedActionCommand(
            String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    public String Execute() throws SQLException {
        return Command();
    }

    @Override
    protected String Command() {
        String user = getCommandArg(0);
        String byteIdStr = getCommandArg(1);

        if (user == null || byteIdStr == null) {
            return "RESULT ERROR Usage: stormtestseedaction <username> <byteId>";
        }

        try {
            byte targetId = Byte.parseByte(byteIdStr);

            IsoPlayer player = GameServer.getPlayerByUserName(user);
            if (player == null) return "RESULT ERROR player_not_found:" + user;

            NetTimedAction action = createFakeAction(player, targetId, "seed-" + user);
            ActionManager.add(action);

            int count = countActionsWithId(targetId);
            short onlineId = player.getOnlineID();

            return "RESULT SEEDED id="
                    + targetId
                    + " count="
                    + count
                    + " player="
                    + user
                    + " onlineId="
                    + onlineId;

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

    private static NetTimedAction createFakeAction(IsoPlayer player, byte targetId, String name)
            throws Exception {
        NetTimedAction nta = new NetTimedAction();

        Class<?> actionClass = getActionClass();

        Field idField = actionClass.getDeclaredField("id");
        idField.setAccessible(true);
        idField.setByte(nta, targetId);

        Field stateField = actionClass.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(nta, Transaction.TransactionState.Accept);

        Field playerIdField = actionClass.getDeclaredField("playerId");
        playerIdField.setAccessible(true);
        Object playerId = playerIdField.get(nta);
        playerId.getClass().getMethod("set", IsoPlayer.class).invoke(playerId, player);

        Field startTimeField = actionClass.getDeclaredField("startTime");
        startTimeField.setAccessible(true);
        startTimeField.setLong(nta, System.currentTimeMillis());

        Field endTimeField = actionClass.getDeclaredField("endTime");
        endTimeField.setAccessible(true);
        endTimeField.setLong(nta, Long.MAX_VALUE);

        nta.duration = 999_999_999L;
        nta.type = "FakeTestAction";
        nta.name = name;
        nta.action = new KahluaTableImpl(new HashMap<>());

        return nta;
    }

    private static Class<?> getActionClass() {
        return NetTimedAction.class.getSuperclass();
    }
}
