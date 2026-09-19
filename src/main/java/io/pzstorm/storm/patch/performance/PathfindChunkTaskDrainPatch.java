package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Drains the native pathfinder's chunk task queue by time budget at the top of each {@code
 * PathfindNativeThread.updateThread()} frame, so the queue cannot outgrow vanilla's ten updates per
 * frame and pin a direct buffer per queued task for the life of the process.
 *
 * <p>See {@link PathfindChunkTaskDrain} for the measurement and the reasoning.
 *
 * <p>The target is matched by name and arity, never by a hash of the class, so it survives both an
 * upstream Project Zomboid build and any transformer registered ahead of it.
 */
public class PathfindChunkTaskDrainPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.pathfindchunktaskdrain.";

    public PathfindChunkTaskDrainPatch() {
        super("zombie.pathfind.nativeCode.PathfindNativeThread");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "UpdateThreadAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("updateThread")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
