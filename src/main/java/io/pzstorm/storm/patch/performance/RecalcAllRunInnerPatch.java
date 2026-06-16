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
 * Substitutes {@code this.toThread.take()} inside {@code
 * ServerChunkLoader.RecalcAllThread.runInner} with {@link StormChunkRecalcGate#takeOrPark}, so each
 * worker is gated on its slot index. Inactive slots park instead of draining the shared queue,
 * which lets {@link StormChunkRecalcConfig#PRE_ALLOCATED} workers stay alive while only the
 * configured count receives work.
 *
 * <p>Scoped to {@code runInner} only; other {@code LinkedBlockingQueue.take()} calls in the class
 * are untouched.
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
            return builder.visit(
                    MemberSubstitution.relaxed()
                            .method(
                                    ElementMatchers.isDeclaredBy(LinkedBlockingQueue.class)
                                            .and(ElementMatchers.named("take"))
                                            .and(ElementMatchers.takesArguments(0)))
                            .replaceWith(
                                    StormChunkRecalcGate.class.getDeclaredMethod(
                                            "takeOrPark", LinkedBlockingQueue.class))
                            .on(runInnerMatcher));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(
                    "Failed to setup MemberSubstitution for RecalcAllThread.runInner", e);
        }
    }
}
