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

@CommandName(name = "stormtestremovebug")
@CommandHelp(
        helpText =
                "Tests ActionManager.remove byte-id collision bug:"
                        + " stormtestremovebug <userA> <userB> <byteId>",
        shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DebugConsole)
public class TestActionRemoveBugCommand extends CommandBase {

    public TestActionRemoveBugCommand(
            String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    public String Execute() throws SQLException {
        return Command();
    }

    @Override
    protected String Command() {
        String userA = getCommandArg(0);
        String userB = getCommandArg(1);
        String byteIdStr = getCommandArg(2);

        if (userA == null || userB == null || byteIdStr == null) {
            return "RESULT ERROR Usage: stormtestremovebug <userA> <userB> <byteId>";
        }

        try {
            byte targetId = Byte.parseByte(byteIdStr);

            IsoPlayer playerA = GameServer.getPlayerByUserName(userA);
            IsoPlayer playerB = GameServer.getPlayerByUserName(userB);
            if (playerA == null) return "RESULT ERROR player_not_found:" + userA;
            if (playerB == null) return "RESULT ERROR player_not_found:" + userB;

            NetTimedAction actionA = createFakeAction(playerA, targetId, "fakeA");
            NetTimedAction actionB = createFakeAction(playerB, targetId, "fakeB");

            ActionManager.add(actionA);
            ActionManager.add(actionB);

            int before = countActionsWithId(targetId);

            // Call stop(actionA) — the same path the real game takes when a client
            // cancels an action.  The vanilla bug: stop() delegates to
            // remove(action.id, true) which filters by byte id alone, nuking both
            // players' actions.
            ActionManager.stop(actionA);

            int after = countActionsWithId(targetId);

            short idA = playerA.getOnlineID();
            short idB = playerB.getOnlineID();

            return "RESULT BEFORE="
                    + before
                    + " AFTER="
                    + after
                    + " PLAYER_A="
                    + idA
                    + " PLAYER_B="
                    + idB;

        } catch (Exception e) {
            return "RESULT ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private static int countActionsWithId(byte id) throws Exception {
        Field actionsField = ActionManager.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        ConcurrentLinkedQueue<?> queue = (ConcurrentLinkedQueue<?>) actionsField.get(null);
        int count = 0;
        for (Object obj : queue) {
            Field idField = getActionClass().getDeclaredField("id");
            idField.setAccessible(true);
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
