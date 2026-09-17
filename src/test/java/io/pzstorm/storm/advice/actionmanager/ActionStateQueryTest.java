package io.pzstorm.storm.advice.actionmanager;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Collection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import zombie.core.ActionManager;
import zombie.core.NetTimedAction;
import zombie.core.Transaction;

/**
 * Exercises {@link ActionStateQuery} against the real {@code ActionManager.actions} queue. The
 * cases that differ from vanilla are the absent-id ones: vanilla answers "done" when other actions
 * are queued and "neither" when the list is empty; the patch answers "rejected" in both.
 */
class ActionStateQueryTest {

    private static final byte ID = 7;
    private static final byte OTHER = 9;

    @AfterEach
    void clearQueue() throws Exception {
        queue().clear();
    }

    @Test
    void absentIdOnEmptyListIsRejectedNotDone() throws Exception {
        assertFalse(ActionStateQuery.isDone(ID));
        assertTrue(ActionStateQuery.isRejected(ID));
    }

    @Test
    void absentIdWithOtherActionsQueuedIsRejectedNotDone() throws Exception {
        add(OTHER, Transaction.TransactionState.Accept);
        assertFalse(ActionStateQuery.isDone(ID));
        assertTrue(ActionStateQuery.isRejected(ID));
    }

    @Test
    void pendingActionIsNeither() throws Exception {
        add(ID, Transaction.TransactionState.Request);
        assertFalse(ActionStateQuery.isDone(ID));
        assertFalse(ActionStateQuery.isRejected(ID));
        add(ID, Transaction.TransactionState.Accept);
        assertFalse(ActionStateQuery.isDone(ID));
        assertFalse(ActionStateQuery.isRejected(ID));
    }

    @Test
    void doneActionIsDone() throws Exception {
        add(ID, Transaction.TransactionState.Done);
        assertTrue(ActionStateQuery.isDone(ID));
        assertFalse(ActionStateQuery.isRejected(ID));
    }

    @Test
    void rejectedActionIsRejected() throws Exception {
        add(ID, Transaction.TransactionState.Reject);
        assertFalse(ActionStateQuery.isDone(ID));
        assertTrue(ActionStateQuery.isRejected(ID));
    }

    @Test
    void mixedStatesForSameIdMatchVanillaAllMatch() throws Exception {
        add(ID, Transaction.TransactionState.Done);
        add(ID, Transaction.TransactionState.Request);
        assertFalse(ActionStateQuery.isDone(ID));
        assertFalse(ActionStateQuery.isRejected(ID));
    }

    private static void add(byte id, Transaction.TransactionState state) throws Exception {
        NetTimedAction action = new NetTimedAction();
        Class<?> actionClass = Class.forName("zombie.core.Action");
        Field idField = actionClass.getDeclaredField("id");
        idField.setAccessible(true);
        idField.setByte(action, id);
        action.setState(state);
        queue().add(action);
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> queue() throws Exception {
        Field f = ActionManager.class.getDeclaredField("actions");
        f.setAccessible(true);
        return (Collection<Object>) f.get(null);
    }
}
