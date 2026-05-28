package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Server-only patch that hands {@code ServerLOS$LOSThread.runInner} off to the parallel LOS engine.
 * Registered only under {@code StormEnv.isStormServer()} — must never transform a client JVM (HARD
 * RULE), although {@code ServerLOS} runs only on the dedicated server anyway.
 */
public class ServerLOSRunInnerPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.serverlosruninner.";

    public ServerLOSRunInnerPatch() {
        super("zombie.network.ServerLOS$LOSThread");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "ServerLOSRunInnerAdvice").resolve(), locator)
                        .on(ElementMatchers.named("runInner")));
    }
}
