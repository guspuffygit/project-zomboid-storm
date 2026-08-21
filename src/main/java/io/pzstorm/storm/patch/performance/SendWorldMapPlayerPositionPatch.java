package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * World-map player-position fan-out: times the once-a-second all-connections batch and memoizes the
 * per-pair visibility predicate for its duration (see {@code StormWorldMapVisibilityMemo}).
 */
public class SendWorldMapPlayerPositionPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.sendworldmapplayerposition.";

    public SendWorldMapPlayerPositionPatch() {
        super("zombie.network.GameServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "SendWorldMapPlayerPositionAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("sendWorldMapPlayerPosition")
                                                .and(ElementMatchers.takesArguments(0))))
                .visit(
                        Advice.to(
                                        typePool.describe(
                                                        PKG
                                                                + "ShouldSendWorldMapPlayerPositionAdvice")
                                                .resolve(),
                                        locator)
                                .on(ElementMatchers.named("shouldSendWorldMapPlayerPosition")));
    }
}
