package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Lets the operator set a custom zombie-cull threshold beyond vanilla's 500 cap, and fixes
 * vanilla's over-cull bug so the live zombie count converges to the threshold instead of being
 * mass-deleted whenever it overshoots.
 *
 * <p>Two advices on {@code zombie.popman.ZombieCountOptimiser}:
 *
 * <ul>
 *   <li>{@code startCount} — {@code ZombieCullThresholdStartAdvice} overwrites the computed {@code
 *       zombieCountForDelete} against {@link StormZombieCullConfig#getThreshold()} when set,
 *       bypassing the sandbox option's 500 cap.
 *   <li>{@code incrementZombie} — {@code ZombieCullThresholdIncrementAdvice} decrements {@code
 *       zombieCountForDelete} when vanilla queues a zombie, so the cull stops at exactly the excess
 *       instead of running away to ~10% of total population per frame.
 * </ul>
 *
 * <p>Always registered on the server JVM (see {@code StormClassTransformers}); both advices are
 * no-ops when {@link StormZombieCullConfig#getThreshold()} is {@code 0} (culling disabled outright
 * via {@code ZombieCullDisablePatch}). Sourced from the {@code Storm.ZombieCullThreshold} sandbox
 * option; live updates via the {@code /storm/server/zombieCull/threshold} HTTP endpoint take effect
 * on the next frame.
 */
public class ZombieCullThresholdPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.zombiecullthreshold.";

    public ZombieCullThresholdPatch() {
        super("zombie.popman.ZombieCountOptimiser");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "ZombieCullThresholdStartAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("startCount")
                                                .and(ElementMatchers.takesArguments(0))))
                .visit(
                        Advice.to(
                                        typePool.describe(
                                                        PKG + "ZombieCullThresholdIncrementAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("incrementZombie")
                                                .and(ElementMatchers.takesArguments(1))));
    }
}
