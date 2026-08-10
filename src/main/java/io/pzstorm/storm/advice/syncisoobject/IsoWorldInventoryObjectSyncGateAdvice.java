package io.pzstorm.storm.advice.syncisoobject;

import io.pzstorm.storm.connection.StormSyncIsoObjectGate;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Routes the {@code IsoWorldInventoryObject.syncIsoObject} override through {@link
 * StormSyncIsoObjectGate#runWorldInventoryObject}: a {@code true} verdict means the relevancy-gated
 * path ran and the vanilla broadcast body is skipped; {@code false} (client JVM guard or failure
 * latch) leaves the vanilla body to run untouched. Arguments are typed {@code Object} so the
 * inlined advice never references the transform target's dependencies.
 */
public class IsoWorldInventoryObjectSyncGateAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.This Object obj,
            @Advice.Argument(0) boolean bRemote,
            @Advice.Argument(2) Object source,
            @Advice.Argument(3) Object bb) {
        if (!GameServer.server) {
            return false;
        }
        return StormSyncIsoObjectGate.runWorldInventoryObject(obj, bRemote, source, bb);
    }
}
