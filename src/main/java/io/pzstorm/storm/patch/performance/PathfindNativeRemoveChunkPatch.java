package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

public class PathfindNativeRemoveChunkPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.pathfindnativeremovechunk.";

    public PathfindNativeRemoveChunkPatch() {
        super("zombie.pathfind.nativeCode.PathfindNative");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        // Woven this way round the skip ends up outermost: a remove skipped for an evicted warm
        // cell (StormCellWarmer.evictLiteReconnect) is not timed, every remove that runs is.
        return builder.visit(
                        Advice.to(
                                        typePool.describe(
                                                        PKG + "PathfindNativeEvictRemoveSkipAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("removeChunkFromWorld")
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "PathfindNativeRemoveChunkAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("removeChunkFromWorld")
                                                .and(ElementMatchers.takesArguments(1))));
    }
}
