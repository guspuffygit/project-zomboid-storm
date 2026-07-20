package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}. This is a
 * deliberate, user-approved exception to the no-client-patches rule — do not use it as precedent,
 * and do not register it outside the experimental gate.
 *
 * <p>Fronts the {@code TIntObjectHashMap} lookup in {@code ChunkLevelsData.getDataForLevel} with a
 * 64-slot array indexed by {@code level + 32}. See {@link
 * io.pzstorm.storm.advice.cutawaydata.CutawayLevelDataCacheAdvice} for the mechanism and the
 * append-only argument that makes the cache safe. Defines {@code storm$levelCache} on the class to
 * hold the per-instance array.
 */
public class CutawayLevelDataArrayCachePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.cutawaydata.";

    public CutawayLevelDataArrayCachePatch() {
        super("zombie.iso.fboRenderChunk.FBORenderCutaways$ChunkLevelsData");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.defineField("storm$levelCache", Object[].class, Visibility.PUBLIC)
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "CutawayLevelDataCacheAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("getDataForLevel")
                                                .and(ElementMatchers.takesArguments(1))));
    }
}
