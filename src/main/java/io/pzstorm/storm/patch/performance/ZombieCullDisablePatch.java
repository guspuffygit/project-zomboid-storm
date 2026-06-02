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
 * <p>Opt-in and default-off: registered only when {@code -Dstorm.disableZombieCull=true}, and gated
 * server-only at registration time (see {@code StormClassTransformers}). Virtualization in {@code
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
