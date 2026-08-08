package io.pzstorm.storm.advice.bodydamagesync;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.patch.fixes.BodyDamageSyncPatch;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code BodyDamageSync.startSendingUpdates(short localId, short remoteID)}. Skips the
 * vanilla body when {@link BodyDamageSyncPatch#shouldSkipStart} reports that either id does not
 * resolve to a connected player/connection (vanilla would NPE on a vanished patient, or leak a dead
 * updater on an unresolvable recipient).
 */
public class StartSendingUpdatesAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.Argument(0) short localId, @Advice.Argument(1) short remoteId) {
        try {
            return BodyDamageSyncPatch.shouldSkipStart(localId, remoteId);
        } catch (Throwable t) {
            LOGGER.error(
                    "BodyDamageSync start-updates guard failed; falling through to vanilla", t);
            return false;
        }
    }
}
