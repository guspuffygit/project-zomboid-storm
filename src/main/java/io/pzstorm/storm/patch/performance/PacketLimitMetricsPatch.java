package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Makes the client's silent outgoing-packet loss countable.
 *
 * <p>Client-side patch, which needs justifying under the repo's client-Java policy. The cheaper
 * tiers cannot reach this: {@code PacketsCache} keeps only a sliding one-second window of send
 * timestamps and no cumulative counter, so there is no field to sample from Java or Lua, and the
 * drop's only trace is a {@code DebugType.Multiplayer} warn. It is also the last mechanism in the
 * chunk-stall chain with no number attached — every other cause is now measured on both sides.
 *
 * <p>Patching {@code PacketsCache.isLimitExceeded} rather than {@code PacketType.send} because the
 * boolean this returns is what {@code send} branches on; at {@code send}'s exit the decision is
 * gone. The server's call site consults the same method but only logs it, so {@link
 * io.pzstorm.storm.advice.chunkstream.PacketLimitAdvice} gates on {@code GameClient.client} to
 * count only the call that actually cancels a packet.
 *
 * <p>Pure measurement — the advice is exit-only and returns nothing, so it cannot change whether a
 * packet is sent.
 */
public class PacketLimitMetricsPatch extends StormClassTransformer {

    private static final String ADVICE = "io.pzstorm.storm.advice.chunkstream.PacketLimitAdvice";

    public PacketLimitMetricsPatch() {
        super("zombie.network.PacketsCache");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("isLimitExceeded")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
