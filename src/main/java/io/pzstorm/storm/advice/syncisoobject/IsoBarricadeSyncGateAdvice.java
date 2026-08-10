package io.pzstorm.storm.advice.syncisoobject;

import io.pzstorm.storm.connection.StormSyncIsoObjectGate;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Routes the {@code IsoBarricade.syncIsoObject} override through {@link
 * StormSyncIsoObjectGate#runBarricade}: a {@code true} verdict means the relevancy-gated path ran
 * and the vanilla broadcast body is skipped; {@code false} (client JVM guard or failure latch)
 * leaves the vanilla body to run untouched. Only the source connection is needed — the override's
 * receive branch is client-only, and the server loop ignores {@code bRemote}/{@code val}/{@code bb}
 * entirely.
 */
public class IsoBarricadeSyncGateAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object obj, @Advice.Argument(2) Object source) {
        if (!GameServer.server) {
            return false;
        }
        return StormSyncIsoObjectGate.runBarricade(obj, source);
    }
}
