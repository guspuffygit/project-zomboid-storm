package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Times every server tick via {@code StatisticManager.update(long)}, the once-per-frame-step call
 * at the tail of {@code GameServer.main}'s server loop. Its sole {@code long} argument is the
 * wall-clock ms since the previous tick, which {@link
 * io.pzstorm.storm.advice.servertick.ServerTickAdvice} feeds to {@link
 * io.pzstorm.storm.metrics.ServerTickMetrics}.
 *
 * <p>{@code StatisticManager.update(long)} also executes on the client (via {@code GameClient}), so
 * this patch is registration-gated server-only in {@code StormClassTransformers} (HARD RULE: no
 * Storm patch may run on the client JVM).
 */
public class ServerTickPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.servertick.";

    public ServerTickPatch() {
        super("zombie.network.statistics.StatisticManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "ServerTickAdvice").resolve(), locator)
                        .on(ElementMatchers.named("update")));
    }
}
