package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Disables {@code ZombieCountOptimiser}'s culling of surplus live zombies by skipping the body of
 * {@code incrementZombie(IsoZombie)} — nothing is ever added to {@code zombiesForDelete}, so {@code
 * deleteZombies()} is always a no-op and the {@code zombies-culled} stat never increments.
 *
 * <p>Always registered on the dedicated server (see {@code StormClassTransformers}); the advice
 * itself only short-circuits when {@link StormZombieCullConfig#getThreshold()} is {@code 0}, so by
 * default (threshold = 500, matching vanilla) it is a runtime no-op. Setting the {@code
 * Storm.ZombieCullThreshold} sandbox option to {@code 0} flips this on. Virtualization in {@code
 * ZombiePopulationManager} is a separate mechanism and is intentionally left untouched.
 */
public class ZombieCullDisablePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.zombieculldisable.";

    public ZombieCullDisablePatch() {
        super("zombie.popman.ZombieCountOptimiser");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "ZombieCullDisableAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("incrementZombie")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
