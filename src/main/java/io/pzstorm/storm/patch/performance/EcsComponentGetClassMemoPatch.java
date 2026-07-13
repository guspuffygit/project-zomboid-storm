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
 * <p>Memoizes the static {@code ECSComponent.getECSClass(Class)} superclass walk. See {@link
 * io.pzstorm.storm.advice.ecsgetclass.EcsGetClassMemoAdvice} for the rationale and profiling data.
 *
 * <p>Targets only the one-argument static overload; the zero-argument instance accessor already
 * returns a cached field.
 */
public class EcsComponentGetClassMemoPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.ecsgetclass.";

    public EcsComponentGetClassMemoPatch() {
        super("zombie.characters.ecs.ECSComponent");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "EcsGetClassMemoAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("getECSClass")
                                        .and(ElementMatchers.isStatic())
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
