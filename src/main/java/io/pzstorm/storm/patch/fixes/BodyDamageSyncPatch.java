package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.network.GameServer;

/**
 * Hardens {@code BodyDamageSync.startSendingUpdates(short localId, short remoteID)} against ids
 * that do not resolve to a connected player. Vanilla has two defects here:
 *
 * <ul>
 *   <li>{@code GameServer.IDToPlayerMap.get(localId)} is dereferenced without a null check — a
 *       patient who disconnected between the client sending {@code START_UPDATING} and the server
 *       processing it turns into an NPE on the packet-processing path.
 *   <li>An unresolvable recipient (e.g. the {@code -1} the vanilla client sends during the
 *       respawn/reconnect race fixed by {@link BodyDamageUpdatePacketPatch}) registers an {@code
 *       Updater} whose outbound connection lookup always returns null: it ticks every 500&nbsp;ms
 *       forever, sends nothing, and is only removed by a matching {@code STOP_UPDATING} that can
 *       never arrive.
 * </ul>
 *
 * <p>The guard skips registration when either end cannot be resolved. This is behavior-preserving
 * for the working case: an updater for a vanished patient would NPE, and one for a vanished
 * recipient would never send a byte.
 */
public class BodyDamageSyncPatch extends StormClassTransformer {

    public BodyDamageSyncPatch() {
        super("zombie.network.BodyDamageSync");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(
                                                "io.pzstorm.storm.advice.bodydamagesync.StartSendingUpdatesAdvice")
                                        .resolve(),
                                locator)
                        .on(ElementMatchers.named("startSendingUpdates")));
    }

    /**
     * Returns {@code true} when the updater registration should be skipped because the patient
     * ({@code localId}) has no connected player or the recipient ({@code remoteId}) has no
     * connection.
     */
    public static boolean shouldSkipStart(short localId, short remoteId) {
        if (!GameServer.server) {
            return false;
        }
        if (GameServer.IDToPlayerMap.get(localId) == null) {
            LOGGER.debug(
                    "BodyDamageSync.startSendingUpdates skipped: patient onlineId {} is not a connected player",
                    localId);
            return true;
        }
        if (GameServer.getConnectionByPlayerOnlineID(remoteId) == null) {
            LOGGER.debug(
                    "BodyDamageSync.startSendingUpdates skipped: recipient onlineId {} has no connection",
                    remoteId);
            return true;
        }
        return false;
    }
}
