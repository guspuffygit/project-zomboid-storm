package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import zombie.core.NetTimedAction;
import zombie.network.GameServer;

/**
 * Runtime logic for {@link NetTimedActionParsePatch}, kept out of the patch class so that the patch
 * never references a game type during transformer registration.
 */
public final class NetTimedActionParseFix {

    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    private NetTimedActionParseFix() {}

    /**
     * Called when {@code NetTimedAction.parse} threw. Clears the half-built action so {@code
     * processServer} takes its Reject path.
     *
     * @return {@code true} to swallow the exception, {@code false} to let it propagate as vanilla
     *     would (only outside the server JVM).
     */
    public static boolean onParseFailed(NetTimedAction self, Throwable thrown) {
        if (!GameServer.server) {
            return false;
        }
        self.action = null;
        String type = self.type == null || self.type.isEmpty() ? "?" : self.type;
        String key = type + "|" + thrown.getClass().getName();
        if (reported.add(key)) {
            LOGGER.warn(
                    "{} timed action '{}' request could not be deserialized ({}: {}) - rejecting it"
                            + " so the client's action queue does not hang. Further occurrences for"
                            + " this action type are logged at debug level.",
                    NtaDebugLog.PREFIX,
                    type,
                    thrown.getClass().getSimpleName(),
                    thrown.getMessage(),
                    thrown);
        } else {
            LOGGER.debug(
                    "{} timed action '{}' request rejected: {}: {}",
                    NtaDebugLog.PREFIX,
                    type,
                    thrown.getClass().getSimpleName(),
                    thrown.getMessage());
        }
        return true;
    }
}
