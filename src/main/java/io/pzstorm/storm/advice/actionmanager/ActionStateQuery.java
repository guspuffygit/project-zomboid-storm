package io.pzstorm.storm.advice.actionmanager;

import java.lang.reflect.Field;
import java.util.Collection;
import zombie.core.ActionManager;
import zombie.core.Transaction;

/**
 * Replacement bodies for {@code ActionManager.isDone(byte)} and {@code isRejected(byte)}.
 *
 * <p>Vanilla prefixes both with {@code !actions.isEmpty()}. That makes an id that is absent from
 * the list mean three different things depending on what else is queued: "done" when other actions
 * are present ({@code allMatch} over an empty stream), "nothing" when the list is empty, and never
 * "rejected" on its own. The empty case is the one players hit: the 30-minute client timeout
 * removes a stalled action and from then on neither query returns true, so the action is stuck for
 * good.
 *
 * <p>Here an absent id always reads as rejected and never as done, so a removed or never-sent
 * action force-stops instead of hanging or completing client-side. An id that is present reads
 * exactly as vanilla.
 */
public final class ActionStateQuery {

    private static volatile Field actionsField;
    private static volatile Field idField;
    private static volatile Field stateField;

    private ActionStateQuery() {}

    private static synchronized void initFieldHandles() throws ReflectiveOperationException {
        if (actionsField != null) return;
        Class<?> actionClass = Class.forName("zombie.core.Action");
        Field idf = actionClass.getDeclaredField("id");
        idf.setAccessible(true);
        Field sf = actionClass.getDeclaredField("state");
        sf.setAccessible(true);
        Field af = ActionManager.class.getDeclaredField("actions");
        af.setAccessible(true);
        idField = idf;
        stateField = sf;
        actionsField = af;
    }

    public static boolean isDone(byte id) throws ReflectiveOperationException {
        int[] counts = count(id, Transaction.TransactionState.Done);
        return counts[0] > 0 && counts[1] == counts[0];
    }

    public static boolean isRejected(byte id) throws ReflectiveOperationException {
        int[] counts = count(id, Transaction.TransactionState.Reject);
        return counts[1] == counts[0];
    }

    /** Returns {matching id, matching id and in the given state}. */
    private static int[] count(byte id, Transaction.TransactionState state)
            throws ReflectiveOperationException {
        if (actionsField == null) {
            initFieldHandles();
        }
        int matches = 0;
        int inState = 0;
        for (Object action : (Collection<?>) actionsField.get(null)) {
            if (idField.getByte(action) != id) {
                continue;
            }
            matches++;
            if (stateField.get(action) == state) {
                inState++;
            }
        }
        return new int[] {matches, inState};
    }
}
