package io.pzstorm.storm.advice.gameentitynetwork;

import io.pzstorm.storm.connection.StormGameEntityBroadcastGate;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Routes {@code GameEntityNetwork.sendPacketData(EntityPacketData, GameEntity, Component,
 * IConnection, boolean)} through {@link StormGameEntityBroadcastGate#run}: a {@code true} verdict
 * means the relevancy-gated broadcast ran and the vanilla body is skipped; {@code false} (client
 * JVM guard, targeted send, ungateable entity, or failure latch) leaves the vanilla body to run
 * untouched. Arguments are typed {@code Object} so the inlined advice never references the
 * transform target's dependencies.
 */
public class GameEntitySendPacketDataAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.Argument(0) Object data,
            @Advice.Argument(1) Object entity,
            @Advice.Argument(2) Object component,
            @Advice.Argument(3) Object connection,
            @Advice.Argument(4) boolean isIgnoreConnection) {
        if (!GameServer.server) {
            return false;
        }
        return StormGameEntityBroadcastGate.run(
                data, entity, component, connection, isIgnoreConnection);
    }
}
