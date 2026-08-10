package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces the server paths of the base {@code IsoObject.syncIsoObject(boolean, byte,
 * UdpConnection, ByteBufferReader)} with the relevancy-gated loops in {@code
 * StormSyncIsoObjectGate}. Vanilla broadcasts every SyncIsoObject full-state packet to every
 * connection; clients without the square loaded discard it on arrival, making out-of-range sends
 * pure waste (972 KB/s at 103 players — the #2 outbound packet type). The gate is vanilla's own
 * {@code IsoDoor} precedent: {@code isFullyConnected() && isRelevantTo(x, y)}.
 *
 * <p>This base-method patch covers every subclass without its own {@code syncIsoObject} override —
 * including {@code IsoHutch}, whose per-egg inventory serialization makes the largest payloads.
 * Overrides with their own loops are patched separately ({@code
 * IsoWorldInventoryObjectSyncGatePatch}, {@code IsoBarricadeSyncGatePatch}, {@code
 * IsoLightSwitchSyncGatePatch}); {@code IsoDoor} is already gated in vanilla, and {@code
 * IsoStove}/{@code IsoCurtain} stay vanilla (private receive state, low-volume senders).
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}) — {@code IsoObject} is
 * loaded by clients too, and the advice additionally guards on {@code GameServer.server} at
 * runtime, which subsumes vanilla's {@code GameClient.client} branch ordering.
 *
 * <p>Always on; the gated path permanently reverts to vanilla if it ever throws.
 */
public class IsoObjectSyncGatePatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.IsoObject";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.syncisoobject.IsoObjectSyncGateAdvice";

    public IsoObjectSyncGatePatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(
                        ElementMatchers.named("syncIsoObject")
                                .and(ElementMatchers.takesArguments(4)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "IsoObjectSyncGatePatch: IsoObject no longer declares the 4-arg"
                            + " syncIsoObject — the name-string hook would silently no-op and"
                            + " reintroduce the ungated broadcast-to-every-connection sync loops."
                            + " Re-verify the patch against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("syncIsoObject")
                                        .and(ElementMatchers.takesArguments(4))));
    }
}
