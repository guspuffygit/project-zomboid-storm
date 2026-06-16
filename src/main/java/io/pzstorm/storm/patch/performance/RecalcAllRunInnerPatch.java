package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import java.util.concurrent.LinkedBlockingQueue;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Two substitutions inside {@code ServerChunkLoader.RecalcAllThread.runInner}:
 *
 * <ul>
 *   <li>{@code this.toThread.take()} → {@link StormChunkRecalcGate#takeOrPark} — gates each worker
 *       on its slot index so inactive slots park instead of pulling. With the pre-allocated pool of
 *       {@link StormChunkRecalcConfig#PRE_ALLOCATED} workers, this is what makes "only N active"
 *       possible without rebuilding the pool.
 *   <li>{@code this.fromThread.add(cell)} → {@link StormChunkPreloadHelper#preloadAndAdd} — when
 *       {@link StormChunkPreloadConfig#isEnabled()} is {@code true}, runs each chunk's {@code
 *       IsoChunk.doLoadGridsquare()} on the worker before publishing the cell; otherwise a no-op
 *       pass-through.
 * </ul>
 *
 * <p>Both substitutions are scoped to {@code runInner} only; other {@code LinkedBlockingQueue}
 * calls in the class are untouched.
 */
public class RecalcAllRunInnerPatch extends StormClassTransformer {

    public RecalcAllRunInnerPatch() {
        super("zombie.network.ServerChunkLoader$RecalcAllThread");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        try {
            ElementMatcher.Junction<MethodDescription> runInnerMatcher =
                    ElementMatchers.named("runInner").and(ElementMatchers.takesArguments(0));
            DynamicType.Builder<Object> result =
                    builder.visit(
                            MemberSubstitution.relaxed()
                                    .method(
                                            ElementMatchers.isDeclaredBy(LinkedBlockingQueue.class)
                                                    .and(ElementMatchers.named("take"))
                                                    .and(ElementMatchers.takesArguments(0)))
                                    .replaceWith(
                                            StormChunkRecalcGate.class.getDeclaredMethod(
                                                    "takeOrPark", LinkedBlockingQueue.class))
                                    .on(runInnerMatcher));
            return result.visit(
                    MemberSubstitution.relaxed()
                            .method(
                                    ElementMatchers.isDeclaredBy(LinkedBlockingQueue.class)
                                            .and(ElementMatchers.named("add"))
                                            .and(ElementMatchers.takesArguments(1)))
                            .replaceWith(
                                    StormChunkPreloadHelper.class.getDeclaredMethod(
                                            "preloadAndAdd",
                                            LinkedBlockingQueue.class,
                                            Object.class))
                            .on(runInnerMatcher));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(
                    "Failed to setup MemberSubstitution for RecalcAllThread.runInner", e);
        }
    }
}
