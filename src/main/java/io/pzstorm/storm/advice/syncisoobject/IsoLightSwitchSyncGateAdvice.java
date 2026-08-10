package io.pzstorm.storm.advice.syncisoobject;

import io.pzstorm.storm.connection.StormSyncIsoObjectGate;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Routes the operative 3-arg {@code IsoLightSwitch.syncIsoObject(boolean, byte, UdpConnection)}
 * through {@link StormSyncIsoObjectGate#runLightSwitch} (the inherited 4-arg override just
 * delegates here): a {@code true} verdict means the relevancy-gated path ran and the vanilla
 * broadcast body is skipped; {@code false} (client JVM guard or failure latch) leaves the vanilla
 * body to run untouched. Only the source connection is needed — on the server the {@code
 * bRemote}/{@code val} arguments feed branches that are dead behind the leading {@code
 * GameServer.server} check.
 */
public class IsoLightSwitchSyncGateAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object obj, @Advice.Argument(2) Object source) {
        if (!GameServer.server) {
            return false;
        }
        return StormSyncIsoObjectGate.runLightSwitch(obj, source);
    }
}
