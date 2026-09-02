package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * {@code zombie.GameProfiler}: {@code startFrame}/{@code endFrame} report their {@code isRunning}
 * transitions to {@link io.pzstorm.storm.profiling.StormGameProfilerGate}, and static {@code
 * isRunning()} short-circuits to {@code false} while the gate says no profiler is on. Pairs with
 * {@link PerformanceProbeGatePatch}. Server-only by registration gate. Fails loud if any target
 * method or the {@code isRunning} field disappears.
 */
public class GameProfilerGatePatch extends StormClassTransformer {

    private static final String TARGET = "zombie.GameProfiler";
    private static final String ADVICE_PKG = "io.pzstorm.storm.advice.profilergate.";

    public GameProfilerGatePatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        var type = typePool.describe(TARGET).resolve();
        if (type.getDeclaredFields().filter(ElementMatchers.named("isRunning")).isEmpty()
                || type.getDeclaredMethods().filter(ElementMatchers.named("startFrame")).isEmpty()
                || type.getDeclaredMethods().filter(ElementMatchers.named("endFrame")).isEmpty()
                || type.getDeclaredMethods()
                        .filter(ElementMatchers.named("isRunning").and(ElementMatchers.isStatic()))
                        .isEmpty()) {
            throw new IllegalStateException(
                    "GameProfilerGatePatch: GameProfiler shape changed — re-verify against the"
                            + " current game source.");
        }
        return builder.visit(
                        Advice.to(
                                        typePool.describe(
                                                        ADVICE_PKG
                                                                + "GameProfilerFrameTransitionAdvice")
                                                .resolve(),
                                        locator)
                                .on(ElementMatchers.namedOneOf("startFrame", "endFrame")))
                .visit(
                        Advice.to(
                                        typePool.describe(
                                                        ADVICE_PKG
                                                                + "GameProfilerIsRunningGateAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("isRunning")
                                                .and(ElementMatchers.isStatic())
                                                .and(ElementMatchers.takesArguments(0))));
    }
}
