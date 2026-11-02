package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import zombie.GameTime;
import zombie.core.ActionManager;
import zombie.core.NetTimedAction;
import zombie.core.Transaction;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.fields.character.Player;

/**
 * Shared utility for NetTimedAction debug logging. All log messages use a consistent {@value
 * #PREFIX} prefix for easy filtering by Steam ID.
 *
 * <p>Traces are emitted only for the players named in {@value #DEBUG_PLAYERS_PROPERTY}, a
 * comma-separated list of Steam IDs and/or usernames read once at class load. The allow list is
 * empty by default so nothing is traced on an unconfigured JVM.
 */
public class NtaDebugLog {

    public static final String PREFIX = "[STORM-NTA]";

    public static final String DEBUG_PLAYERS_PROPERTY = "storm.nta.debugPlayers";

    private static volatile Set<String> allowedPlayers = readAllowedPlayers();

    private static Set<String> readAllowedPlayers() {
        String raw = System.getProperty(DEBUG_PLAYERS_PROPERTY, "");
        Set<String> players = new HashSet<>();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                players.add(trimmed);
            }
        }
        return Set.copyOf(players);
    }

    // --- Reflection cache for Action (package-private) fields ---

    private static volatile Field stateField;
    private static volatile Field idField;
    private static volatile Field playerField;
    private static volatile Field startTimeField;
    private static volatile Field endTimeField;
    private static volatile Field actionsField;
    private static volatile boolean fieldsInitialized;

    private static synchronized void initFields() {
        if (fieldsInitialized) {
            return;
        }
        try {
            Class<?> actionClass = Class.forName("zombie.core.Action");
            Field sf = actionClass.getDeclaredField("state");
            sf.setAccessible(true);
            Field idf = actionClass.getDeclaredField("id");
            idf.setAccessible(true);
            Field pf = actionClass.getDeclaredField("player");
            pf.setAccessible(true);
            Field stf = actionClass.getDeclaredField("startTime");
            stf.setAccessible(true);
            Field etf = actionClass.getDeclaredField("endTime");
            etf.setAccessible(true);
            Field af = ActionManager.class.getDeclaredField("actions");
            af.setAccessible(true);

            // Publish all at once - volatile write of stateField acts as release fence
            idField = idf;
            playerField = pf;
            startTimeField = stf;
            endTimeField = etf;
            actionsField = af;
            stateField = sf;
            fieldsInitialized = true;
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

    public static boolean isAllowedConnection(IConnection connection) {
        if (connection == null || allowedPlayers.isEmpty()) {
            return false;
        }
        if (allowedPlayers.contains(String.valueOf(connection.getSteamId()))) {
            return true;
        }
        if (connection instanceof UdpConnection udp && udp.usernames != null) {
            for (String username : udp.usernames) {
                if (username != null && allowedPlayers.contains(username)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isAllowedClient() {
        if (!GameClient.client || allowedPlayers.isEmpty()) {
            return false;
        }
        return allowedPlayers.contains(String.valueOf(GameClient.steamID))
                || allowedPlayers.contains(GameClient.username);
    }

    // --- Field accessors ---

    public static byte getId(Object action) {
        try {
            ensureFields();
            return idField.getByte(action);
        } catch (Exception e) {
            return -1;
        }
    }

    public static Transaction.TransactionState getState(Object action) {
        try {
            ensureFields();
            return (Transaction.TransactionState) stateField.get(action);
        } catch (Exception e) {
            return null;
        }
    }

    public static Player getPlayer(Object action) {
        try {
            ensureFields();
            return (Player) playerField.get(action);
        } catch (Exception e) {
            return null;
        }
    }

    public static long getStartTime(Object action) {
        try {
            ensureFields();
            return startTimeField.getLong(action);
        } catch (Exception e) {
            return -1;
        }
    }

    public static long getEndTime(Object action) {
        try {
            ensureFields();
            return endTimeField.getLong(action);
        } catch (Exception e) {
            return -1;
        }
    }

    private static volatile ConcurrentLinkedQueue<Object> cachedQueue;

    @SuppressWarnings("unchecked")
    public static ConcurrentLinkedQueue<Object> getActionsQueue() {
        if (cachedQueue != null) {
            return cachedQueue;
        }
        try {
            ensureFields();
            ConcurrentLinkedQueue<Object> q =
                    (ConcurrentLinkedQueue<Object>) actionsField.get(null);
            cachedQueue = q;
            return q;
        } catch (Exception e) {
            return new ConcurrentLinkedQueue<>();
        }
    }

    // --- Formatting ---

    public static String describe(Object action) {
        if (action == null) {
            return "null";
        }
        try {
            byte id = getId(action);
            Transaction.TransactionState state = getState(action);
            Player pid = getPlayer(action);
            long duration = -1;
            String type = action.getClass().getSimpleName();
            String name = "";
            if (action instanceof NetTimedAction nta) {
                duration = nta.duration;
                type = nta.type;
                name = nta.name;
            }
            long startTime = getStartTime(action);
            long endTime = getEndTime(action);
            int playerId = (pid != null) ? pid.getId() : -1;
            String playerName =
                    (pid != null && pid.getPlayer() != null) ? pid.getPlayer().getUsername() : "?";

            String connInfo = "";
            if (GameServer.server && pid != null && pid.getPlayer() != null) {
                UdpConnection conn = GameServer.getConnectionFromPlayer(pid.getPlayer());
                connInfo = " conn=" + (conn != null ? "OK" : "NULL!");
            }

            long now = gameTime();
            return String.format(
                    "{id=%d state=%s type=%s name=%s player=%s(%d) dur=%d start=%d end=%d now=%d%s}",
                    id,
                    state,
                    type,
                    name,
                    playerName,
                    playerId,
                    duration,
                    startTime,
                    endTime,
                    now,
                    connInfo);
        } catch (Exception e) {
            return "{error=" + e.getMessage() + "}";
        }
    }

    public static String describeQueue() {
        try {
            ConcurrentLinkedQueue<Object> q = getActionsQueue();
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object a : q) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(describe(a));
                first = false;
            }
            sb.append("] (").append(q.size()).append(" total)");
            return sb.toString();
        } catch (Exception e) {
            return "[error]";
        }
    }

    // --- Logging ---

    public static void log(String side, String msg) {
        LOGGER.debug("{} [{}] gt={} {}", PREFIX, side, gameTime(), msg);
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
