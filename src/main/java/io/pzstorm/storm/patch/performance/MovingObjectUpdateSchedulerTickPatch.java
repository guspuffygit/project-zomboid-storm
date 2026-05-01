package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Instruments {@code MovingObjectUpdateScheduler.startFrame()} to count server ticks for the
 * animal-update timing metrics. {@code startFrame} is called once at the top of each tick and
 * provides a stable boundary for averaging per-tick animal-update CPU cost.
 */
public class MovingObjectUpdateSchedulerTickPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.animalupdatetiming.";

    public MovingObjectUpdateSchedulerTickPatch() {
        super("zombie.MovingObjectUpdateScheduler");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(
                                                PKG + "MovingObjectUpdateSchedulerStartFrameAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("startFrame")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
