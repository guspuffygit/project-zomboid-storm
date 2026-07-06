package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Strides the unowned-zombie ownership rescan in {@code NetworkZombieManager.updateAuth(IsoZombie)}
 * to every Nth tick per zombie. See {@link ZombieAuthTickInterval} for the behavior analysis;
 * configured via the {@code Storm.ZombieAuthTickInterval} sandbox option.
 *
 * <p>Must be registered <em>after</em> {@code NetworkZombieManagerAuthPatch} so this skip wraps
 * outside the timing advice — skipped calls then record no sample and the auth metrics reflect real
 * scans only.
 */
public class ZombieAuthStridePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.zombieauthstride.";

    public ZombieAuthStridePatch() {
        super("zombie.popman.NetworkZombieManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "NetworkZombieAuthStrideAdvice").resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("updateAuth")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
