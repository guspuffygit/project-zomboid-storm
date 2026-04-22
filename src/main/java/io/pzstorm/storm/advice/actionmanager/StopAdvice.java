package io.pzstorm.storm.advice.actionmanager;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.bytebuddy.asm.Advice;
import zombie.core.ActionManager;
import zombie.core.NetTimedAction;
import zombie.debug.DebugType;
import zombie.network.GameServer;
import zombie.network.server.AnimEventEmulator;

/**
 * Advice for {@code ActionManager.stop(Action)}. On the server side, delegates to {@link
 * StopAdvice#fixedServerStop} which filters by both byte id <em>and</em> player id instead of byte
 * id alone.
 */
public class StopAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(@Advice.Argument(0) Object action) {
        if (!GameServer.server) {
            return false;
        }
        try {
            StopAdvice.fixedServerStop(action);
            return true;
        } catch (Exception e) {
            LOGGER.error("ActionManagerPatch.fixedServerStop failed, falling back to vanilla", e);
            return false;
        }
    }

    private static volatile Field idField;
    private static volatile Field playerIdField;
    private static volatile Field actionsField;
    private static volatile Method actionStopMethod;
    private static volatile Method getPlayerOnlineIdMethod;
    private static volatile Method getDescriptionMethod;

    private static synchronized void initFieldHandles() throws ReflectiveOperationException {
        if (idField != null) return;
        Class<?> actionClass = Class.forName("zombie.core.Action");

        Field idf = actionClass.getDeclaredField("id");
        idf.setAccessible(true);

        Field pidf = actionClass.getDeclaredField("playerId");
        pidf.setAccessible(true);

        Field af = ActionManager.class.getDeclaredField("actions");
        af.setAccessible(true);

        Method sm = actionClass.getDeclaredMethod("stop");
        sm.setAccessible(true);

        Method gim = Class.forName("zombie.network.fields.character.PlayerID").getMethod("getID");

        Method gdm = actionClass.getMethod("getDescription");

        actionStopMethod = sm;
        playerIdField = pidf;
        actionsField = af;
        getPlayerOnlineIdMethod = gim;
        getDescriptionMethod = gdm;
        idField = idf;
    }

    /**
     * Replacement for {@code ActionManager.stop(Action)} on the server side. Identical to vanilla
     * except that the removal filters by <em>both</em> byte id and player online id, preventing one
     * player's cancel from removing another player's action.
     */
    public static void fixedServerStop(Object actionObj) throws ReflectiveOperationException {
        if (idField == null) {
            initFieldHandles();
        }

        byte targetId = idField.getByte(actionObj);
        Object targetPid = playerIdField.get(actionObj);
        short targetOnlineId = (short) getPlayerOnlineIdMethod.invoke(targetPid);

        DebugType.Action.debugln(
                "ActionManager stop action %s", getDescriptionMethod.invoke(actionObj));

        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<Object> queue =
                (ConcurrentLinkedQueue<Object>) actionsField.get(null);

        List<Object> toRemove = new ArrayList<>();
        for (Object a : queue) {
            byte aId = idField.getByte(a);
            Object aPid = playerIdField.get(a);
            short aOnlineId = (short) getPlayerOnlineIdMethod.invoke(aPid);
            if (aId == targetId && aOnlineId == targetOnlineId) {
                toRemove.add(a);
            }
        }

        queue.removeAll(toRemove);

        for (int i = 0; i < toRemove.size(); i++) {
            Object a = toRemove.get(i);
            DebugType.Action.debugln(
                    "ActionManager remove action %s", getDescriptionMethod.invoke(a));
            actionStopMethod.invoke(a);
            if (a instanceof NetTimedAction nta) {
                AnimEventEmulator.getInstance().remove(nta);
            }
        }
    }
}
