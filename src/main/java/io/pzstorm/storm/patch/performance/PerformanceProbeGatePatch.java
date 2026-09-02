package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * {@code zombie.core.profiling.AbstractPerformanceProfileProbe}: {@code start()} and {@code end()}
 * skip their bodies in the states where vanilla would change nothing (see the advices), avoiding
 * the thread-name list scan and {@code ThreadLocal} read on every probe while the profiler is off.
 * Pairs with {@link GameProfilerGatePatch}. Server-only by registration gate. Fails loud if the
 * flag fields or methods disappear.
 */
public class PerformanceProbeGatePatch extends StormClassTransformer {

    private static final String TARGET = "zombie.core.profiling.AbstractPerformanceProfileProbe";
    private static final String ADVICE_PKG = "io.pzstorm.storm.advice.profilergate.";

    public PerformanceProbeGatePatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        var type = typePool.describe(TARGET).resolve();
        if (type.getDeclaredFields().filter(ElementMatchers.named("isRunning")).isEmpty()
                || type.getDeclaredFields()
                        .filter(ElementMatchers.named("isProfilerRunning"))
                        .isEmpty()
                || type.getDeclaredMethods().filter(zeroArg("start")).isEmpty()
                || type.getDeclaredMethods().filter(zeroArg("end")).isEmpty()) {
            throw new IllegalStateException(
                    "PerformanceProbeGatePatch: AbstractPerformanceProfileProbe shape changed —"
                            + " re-verify against the current game source.");
        }
        return builder.visit(
                        Advice.to(
                                        typePool.describe(ADVICE_PKG + "ProbeStartGateAdvice")
                                                .resolve(),
                                        locator)
                                .on(zeroArg("start")))
                .visit(
                        Advice.to(
                                        typePool.describe(ADVICE_PKG + "ProbeEndGateAdvice")
                                                .resolve(),
                                        locator)
                                .on(zeroArg("end")));
    }

    private static net.bytebuddy.matcher.ElementMatcher.Junction<
                    net.bytebuddy.description.method.MethodDescription>
            zeroArg(String name) {
        return ElementMatchers.named(name).and(ElementMatchers.takesArguments(0));
    }
}
