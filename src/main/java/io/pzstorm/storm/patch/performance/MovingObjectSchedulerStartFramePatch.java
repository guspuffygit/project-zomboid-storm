package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Server-only: rebuilds the shared per-tick spatial index ({@code StormSpatialIndex}) at the end of
 * {@code MovingObjectUpdateScheduler.startFrame()}, the first thing {@code IsoCell.update()} does
 * each tick. Consumers ({@code StormPlayerLos}, {@code StormAnimalLos}) query it instead of walking
 * {@code IsoCell.objectList} per viewer. Registration-gated on {@code StormEnv.isStormServer()};
 * the advice also guards on {@code GameServer.server}.
 */
public class MovingObjectSchedulerStartFramePatch extends StormClassTransformer {

    private static final String TARGET = "zombie.MovingObjectUpdateScheduler";
    private static final String PKG = "io.pzstorm.storm.advice.movingobjectschedulerstartframe.";

    public MovingObjectSchedulerStartFramePatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(ElementMatchers.named("startFrame").and(ElementMatchers.takesArguments(0)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "MovingObjectSchedulerStartFramePatch: MovingObjectUpdateScheduler no longer"
                            + " declares startFrame() — the spatial index would never be built"
                            + " and every LOS consumer would silently stay on its full-cell walk."
                            + " Re-verify against the current game source.");
        }
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "MovingObjectSchedulerStartFrameAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("startFrame")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
