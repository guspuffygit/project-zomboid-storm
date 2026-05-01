package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Instruments {@code IsoChunk.removeFromWorld()} with timing advice. Per-call wall-clock
 * nanoseconds are accumulated by {@code io.pzstorm.storm.metrics.ChunkRemoveMetrics} and reported
 * every 60s.
 *
 * <p>Targets the no-arg {@code removeFromWorld()} on {@code zombie.iso.IsoChunk} (line 3133 in the
 * decompiled source). Invoked from {@code ServerMap$ServerCell.Unload} when chunks unload, where
 * JFR shows it driving an O(n) {@code ArrayList.contains} scan in {@code
 * IsoCell.addToProcessIsoObjectRemove} that dominates main-thread cost during cell unload bursts.
 */
public class IsoChunkRemoveFromWorldPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.chunkremovetiming.";

    public IsoChunkRemoveFromWorldPatch() {
        super("zombie.iso.IsoChunk");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "IsoChunkRemoveFromWorldAdvice").resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("removeFromWorld")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
