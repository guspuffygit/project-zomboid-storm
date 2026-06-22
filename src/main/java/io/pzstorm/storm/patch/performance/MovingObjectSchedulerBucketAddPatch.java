package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Server-only patch that skips warm animals at the per-frame bucket-add chokepoint inside {@code
 * MovingObjectUpdateSchedulerUpdateBucket.add(IsoMovingObject)}. The scheduler's update /
 * postupdate / updateAnimation loops drain the buckets, so suppressing the add fully excludes warm
 * animals from per-tick {@code IsoAnimal.update()} work without removing them from {@code
 * IsoCell.objectList} (which {@code AnimalManagerMain.saveRealAnimals()} relies on).
 *
 * <p>Registration-gated to the dedicated server in {@code StormClassTransformers} alongside the
 * other warming-related patches — must never transform a client JVM.
 */
public class MovingObjectSchedulerBucketAddPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.movingobjectschedulerbucketadd.";

    public MovingObjectSchedulerBucketAddPatch() {
        super("zombie.MovingObjectUpdateSchedulerUpdateBucket");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "MovingObjectSchedulerBucketAddAdvice")
                                        .resolve(),
                                locator)
                        .on(ElementMatchers.named("add").and(ElementMatchers.takesArguments(1))));
    }
}
