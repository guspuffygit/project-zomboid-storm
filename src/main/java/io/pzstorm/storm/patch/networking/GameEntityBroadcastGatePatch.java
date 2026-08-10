package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces the server broadcast branch of {@code GameEntityNetwork.sendPacketData(EntityPacketData,
 * GameEntity, Component, IConnection, boolean)} with the relevancy-gated loop in {@code
 * StormGameEntityBroadcastGate}. Vanilla broadcasts every GameEntity packet — craft-progress ticks
 * (re-serialized in full every 1000 ms per running station), component dumps, using-player changes
 * — to every connection via {@code INetworkPacket.sendToAll}; clients without the entity's chunk
 * loaded have no registered entity to apply it to (~1.06 MB/s at ~1080 pkts/s at 103 players — the
 * #1 outbound packet count). The gate is vanilla's own object-sync precedent ({@code
 * INetworkPacket.sendToRelative}): {@code isFullyConnected() && isRelevantTo(x, y)}, applied only
 * to {@code GameEntityType.IsoObject} entities with a square.
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}) — {@code
 * GameEntityNetwork} is loaded by clients too, and the advice additionally guards on {@code
 * GameServer.server} at runtime, which leaves the client send branch untouched.
 *
 * <p>Always on; the gated path permanently reverts to vanilla if it ever throws.
 */
public class GameEntityBroadcastGatePatch extends StormClassTransformer {

    private static final String TARGET = "zombie.entity.GameEntityNetwork";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.gameentitynetwork.GameEntitySendPacketDataAdvice";

    public GameEntityBroadcastGatePatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(
                        ElementMatchers.named("sendPacketData")
                                .and(ElementMatchers.takesArguments(5)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "GameEntityBroadcastGatePatch: GameEntityNetwork no longer declares the 5-arg"
                            + " sendPacketData — the name-string hook would silently no-op and"
                            + " reintroduce the ungated broadcast-to-every-connection GameEntity"
                            + " sends. Re-verify the patch against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("sendPacketData")
                                        .and(ElementMatchers.takesArguments(5))));
    }
}
