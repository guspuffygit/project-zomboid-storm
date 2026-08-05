package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Memoizes the static {@code ECSComponent.getECSClass(Class)} superclass walk through {@code
 * EcsClassCache} (a {@link ClassValue}-backed, outcome-identical cache). Live profiling (79
 * players) attributed ~2.9% self time of the server main thread to this walk — it runs on every
 * {@code getECSComponent}/{@code hasECSComponent}/{@code removeECSComponent}, per character per
 * tick.
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}) — {@code ECSComponent} is
 * loaded by clients too. Distinct from the EXPERIMENTAL client-side {@code
 * EcsComponentGetClassMemoPatch} (opt-in via {@code -Dstorm.experimental.clientperf=true}); if both
 * were ever active in one JVM the advices would stack, but both are outcome-identical caches, so
 * correctness is unaffected.
 *
 * <p>Targets only the one-argument static overload; the zero-argument instance accessor already
 * returns a cached field.
 *
 * <p>Kill switch: the {@code Storm.EcsClassCache} sandbox option ({@code false} restores the
 * vanilla walk; live-appliable via admin sandbox push — always safe, the cache is stateless toward
 * the game). The cache also permanently reverts to vanilla if a lookup ever throws.
 */
public class EcsGetClassCachePatch extends StormClassTransformer {

    private static final String TARGET = "zombie.characters.ecs.ECSComponent";
    private static final String PKG = "io.pzstorm.storm.advice.ecsclasscache.";

    public EcsGetClassCachePatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(
                        ElementMatchers.named("getECSClass")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(1)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "EcsGetClassCachePatch: ECSComponent no longer declares the static"
                            + " getECSClass(Class) overload — the name-string hook would silently"
                            + " no-op and reintroduce the uncached superclass walk. Re-verify the"
                            + " patch against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(PKG + "EcsGetClassCacheAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("getECSClass")
                                        .and(ElementMatchers.isStatic())
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
