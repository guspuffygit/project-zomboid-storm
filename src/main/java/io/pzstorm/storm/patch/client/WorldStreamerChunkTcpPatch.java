package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only companion to {@link ChunkRequestOverTcpPatch}; two concerns on {@code
 * zombie.iso.WorldStreamer}:
 *
 * <ul>
 *   <li>{@code updateMain} exit — dispatch TCP-staged chunk requests only after the method
 *       published them to {@code sentRequests}; earlier delivery would match nothing and stall.
 *   <li>{@code receiveChunkPart} / {@code receiveNotRequired} — wrap in {@code
 *       StormChunksOverTcp.RECEIVE_LOCK}, because Storm's TCP worker delivers through these
 *       vanilla-UdpEngine-thread-only methods and they mutate a non-thread-safe list.
 * </ul>
 *
 * <p>Fail-soft: without a TCP session the staged queue is always empty, the dispatch is a no-op,
 * and the lock is uncontended — vanilla behavior is unchanged.
 */
public class WorldStreamerChunkTcpPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.client.chunksovertcp.";

    public WorldStreamerChunkTcpPatch() {
        super("zombie.iso.WorldStreamer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "UpdateMainDispatchAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("updateMain")
                                                .and(ElementMatchers.takesArguments(0))))
                .visit(
                        Advice.to(typePool.describe(PKG + "ReceiveLockAdvice").resolve(), locator)
                                .on(
                                        ElementMatchers.named("receiveChunkPart")
                                                .or(ElementMatchers.named("receiveNotRequired"))));
    }
}
