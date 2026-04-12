package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import zombie.GameTime;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;

/**
 * Shared utility for ActionStateContainer debug logging. Logs state stack details when child state
 * insertion fails due to tag incompatibility, helping diagnose vanilla game state machine race
 * conditions (e.g. "turning" vs "turningmovement180").
 *
 * <p>All game types are accessed via reflection ({@code Class.forName}) to avoid triggering a
 * {@link ClassCircularityError} when the advice classes are loaded during transformation of {@code
 * ActionStateContainer}.
 */
public class ActionStateDebugLog {

    public static final String PREFIX = "[STORM-ASC]";

    // --- Reflection cache (no direct class references to ActionState/ActionStateContainer) ---

    private static volatile Field currentStateField;
    private static volatile Field childStatesField;
    private static volatile Field tagsField;
    private static volatile Field childTagsField;
    private static volatile Method getNameMethod;
    private static volatile Method canHaveSubStateMethod;
    private static volatile Method getOwnerMethod;
    private static volatile Method getUIDMethod;
    private static volatile Class<?> isoPlayerClass;
    private static volatile Method getConnectionFromPlayerMethod;
    private static volatile boolean fieldsInitialized;

    private static synchronized void initFields() {
        if (fieldsInitialized) {
            return;
        }
        try {
            Class<?> containerClass =
                    Class.forName("zombie.characters.action.ActionStateContainer");
            Class<?> stateClass = Class.forName("zombie.characters.action.ActionState");
            Class<?> contextClass = Class.forName("zombie.characters.action.ActionContext");

            Field csf = containerClass.getDeclaredField("currentState");
            csf.setAccessible(true);
            Field chf = containerClass.getDeclaredField("childStates");
            chf.setAccessible(true);
            Field tf = stateClass.getDeclaredField("tags");
            tf.setAccessible(true);
            Field ctf = stateClass.getDeclaredField("childTags");
            ctf.setAccessible(true);

            Method gnm = stateClass.getMethod("getName");
            Method chsm = stateClass.getMethod("canHaveSubState", stateClass);
            Method gom = contextClass.getMethod("getOwner");

            Class<?> animatableClass =
                    Class.forName("zombie.core.skinnedmodel.advancedanimation.IAnimatable");
            Method guidm = animatableClass.getMethod("getUID");

            // Publish all at once
            childStatesField = chf;
            tagsField = tf;
            childTagsField = ctf;
            getNameMethod = gnm;
            canHaveSubStateMethod = chsm;
            getOwnerMethod = gom;
            getUIDMethod = guidm;
            currentStateField = csf;
            fieldsInitialized = true;

            // Player/connection fields for Steam ID filtering (non-fatal if unavailable)
            try {
                Class<?> ipc = Class.forName("zombie.characters.IsoPlayer");
                Method gcfp = GameServer.class.getMethod("getConnectionFromPlayer", ipc);
                isoPlayerClass = ipc;
                getConnectionFromPlayerMethod = gcfp;
            } catch (Exception e2) {
                LOGGER.warn("{} Could not init player fields for filtering", PREFIX);
            }
        } catch (Exception e) {
            LOGGER.error("{} Failed to init reflection fields", PREFIX, e);
        }
    }

    private static void ensureFields() {
        if (!fieldsInitialized) {
            initFields();
        }
    }

    // --- Steam ID filtering ---

    private static final ThreadLocal<Boolean> ownerAllowed = new ThreadLocal<>();

    /**
     * Checks whether the owner of the given {@code ActionContext} is an allowed player (by Steam
     * ID). On the server, resolves the owner to an {@code IsoPlayer} and checks the connection's
     * Steam ID. On the client, delegates to {@link NtaDebugLog#isAllowedClient()}.
     */
    public static boolean isAllowedOwner(Object actionContext) {
        try {
            ensureFields();
            Object owner = getOwnerMethod.invoke(actionContext);
            if (owner == null) return false;
            if (GameServer.server) {
                if (isoPlayerClass == null || !isoPlayerClass.isInstance(owner)) return false;
                UdpConnection conn =
                        (UdpConnection) getConnectionFromPlayerMethod.invoke(null, owner);
                return NtaDebugLog.isAllowedConnection(conn);
            }
            return NtaDebugLog.isAllowedClient();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sets the ThreadLocal context for the current owner's allowed status. Returns {@code true} if
     * this call established the context (caller is responsible for calling {@link
     * #exitContext(boolean)} with the same value). Returns {@code false} if the context was already
     * set by an outer caller.
     */
    public static boolean enterContext(Object actionContext) {
        if (ownerAllowed.get() != null) {
            return false;
        }
        ownerAllowed.set(isAllowedOwner(actionContext));
        return true;
    }

    /** Clears the ThreadLocal context if this caller was the one that set it. */
    public static void exitContext(boolean didSet) {
        if (didSet) {
            ownerAllowed.remove();
        }
    }

    /** Returns whether the current ThreadLocal context indicates an allowed owner. */
    public static boolean isOwnerAllowed() {
        Boolean allowed = ownerAllowed.get();
        return allowed != null && allowed;
    }

    // --- Field accessors ---

    private static Object getCurrentState(Object container) {
        try {
            ensureFields();
            return currentStateField.get(container);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<Object> getChildStates(Object container) {
        try {
            ensureFields();
            return (ArrayList<Object>) childStatesField.get(container);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String getStateName(Object state) {
        try {
            ensureFields();
            return (String) getNameMethod.invoke(state);
        } catch (Exception e) {
            return "?";
        }
    }

    private static boolean canHaveSubState(Object parent, Object child) {
        try {
            ensureFields();
            return (boolean) canHaveSubStateMethod.invoke(parent, child);
        } catch (Exception e) {
            return false;
        }
    }

    private static String getOwnerUid(Object actionContext) {
        try {
            ensureFields();
            Object owner = getOwnerMethod.invoke(actionContext);
            return (String) getUIDMethod.invoke(owner);
        } catch (Exception e) {
            return "?";
        }
    }

    private static String[] getTags(Object state) {
        try {
            ensureFields();
            return (String[]) tagsField.get(state);
        } catch (Exception e) {
            return null;
        }
    }

    private static String[] getChildTags(Object state) {
        try {
            ensureFields();
            return (String[]) childTagsField.get(state);
        } catch (Exception e) {
            return null;
        }
    }

    // --- Formatting ---

    private static String formatTags(String[] tags) {
        if (tags == null || tags.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tags.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(tags[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private static String describeState(Object state) {
        if (state == null) {
            return "null";
        }
        return getStateName(state)
                + "(tags="
                + formatTags(getTags(state))
                + ", childTags="
                + formatTags(getChildTags(state))
                + ")";
    }

    private static String describeContainer(Object container) {
        if (container == null) {
            return "null";
        }
        Object root = getCurrentState(container);
        ArrayList<Object> children = getChildStates(container);

        StringBuilder sb = new StringBuilder();
        sb.append("root=").append(describeState(root));
        sb.append(" children=[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(describeState(children.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    // --- Advice entry points (called from ActionStateContainerPatch) ---

    public static void logTryInsertEnter(Object self, Object actionContext, Object nextState) {
        if (!isOwnerAllowed()) return;
        Object currentState = getCurrentState(self);
        ArrayList<Object> children = getChildStates(self);

        boolean wouldFail = false;
        String reason = "";

        if (currentState != null && !canHaveSubState(currentState, nextState)) {
            wouldFail = true;
            reason =
                    "currentState "
                            + describeState(currentState)
                            + " does not support child "
                            + describeState(nextState);
        }

        if (!wouldFail) {
            for (int i = 0; i < children.size(); i++) {
                Object child = children.get(i);
                if (child == nextState) {
                    return;
                }
                if (!canHaveSubState(nextState, child) && !canHaveSubState(child, nextState)) {
                    wouldFail = true;
                    reason =
                            "existing child "
                                    + describeState(child)
                                    + " at index "
                                    + i
                                    + " blocks insertion of "
                                    + describeState(nextState);
                    break;
                }
            }
        }

        if (wouldFail) {
            log(
                    "tryInsertChildState WILL FAIL: owner="
                            + getOwnerUid(actionContext)
                            + " reason="
                            + reason
                            + " | "
                            + describeContainer(self));
        }
    }

    public static void logTryInsertExit(
            Object self, Object actionContext, Object nextState, boolean result) {
        if (!isOwnerAllowed()) return;
        if (result) {
            log(
                    "tryInsertChildState OK: owner="
                            + getOwnerUid(actionContext)
                            + " inserted="
                            + getStateName(nextState)
                            + " | "
                            + describeContainer(self));
        }
    }

    public static void logSetCurrentState(Object self, Object nextState) {
        if (!isOwnerAllowed()) return;
        Object currentState = getCurrentState(self);
        if (currentState != null && currentState != nextState) {
            log(
                    "setCurrentState: from="
                            + getStateName(currentState)
                            + " to="
                            + (nextState != null ? getStateName(nextState) : "null")
                            + " | children before: "
                            + describeContainer(self));
        }
    }

    public static void logRemoveChildStateAt(Object self, int subStateIdx) {
        if (!isOwnerAllowed()) return;
        ArrayList<Object> children = getChildStates(self);
        if (subStateIdx >= 0 && subStateIdx < children.size()) {
            Object removed = children.get(subStateIdx);
            log(
                    "removeChildStateAt: idx="
                            + subStateIdx
                            + " removing="
                            + getStateName(removed)
                            + " | "
                            + describeContainer(self));
        }
    }

    // --- Logging ---

    public static void log(String msg) {
        LOGGER.debug("{} [{}] gt={} {}", PREFIX, side(), gameTime(), msg);
    }

    public static String side() {
        return GameServer.server ? "SERVER" : "CLIENT";
    }

    private static long gameTime() {
        try {
            return GameTime.getServerTimeMills();
        } catch (Exception e) {
            return -1;
        }
    }
}
