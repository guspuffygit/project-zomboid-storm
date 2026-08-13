package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}. This is a
 * deliberate, user-approved exception to the no-client-patches rule — do not use it as precedent,
 * and do not register it outside the experimental gate.
 *
 * <p>Replaces the body of {@code FBORenderCutaways.cutawayVisit} (2.7% of MainThread self-time at
 * zoom 2.5): the per-cell {@code HashSet} visited churn becomes an allocation-free identity-table
 * check+mark, and the per-cell chunk-map lookup is hoisted to one chunk resolution per wall. See
 * {@link io.pzstorm.storm.advice.cutawayvisit.CutawayVisitFastPath} for the mechanism, the parity
 * argument and the fail-soft latch.
 */
public class CutawayVisitFastPathPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.cutawayvisit.";

    public CutawayVisitFastPathPatch() {
        super("zombie.iso.fboRenderChunk.FBORenderCutaways");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "CutawayVisitAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("cutawayVisit")
                                        .and(ElementMatchers.takesArguments(3))));
    }
}
