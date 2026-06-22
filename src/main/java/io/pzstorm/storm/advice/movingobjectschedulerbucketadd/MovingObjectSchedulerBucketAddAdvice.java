package io.pzstorm.storm.advice.movingobjectschedulerbucketadd;

import io.pzstorm.storm.patch.performance.StormCellWarmer;
import io.pzstorm.storm.patch.performance.StormCellWarmingConfig;
import net.bytebuddy.asm.Advice;

/**
 * Skips the {@code MovingObjectUpdateSchedulerUpdateBucket.add(IsoMovingObject)} body when warming
 * is enabled and the argument is an animal sitting inside a warmed cell. Returning {@code true}
 * suppresses the body; returning {@code false} runs vanilla. The downstream {@code update} / {@code
 * postupdate} / {@code updateAnimation} loops iterate the buckets, so a warm animal that's never
 * bucketed is fully excluded from per-tick {@code IsoAnimal.update()} work.
 */
public class MovingObjectSchedulerBucketAddAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.Argument(0) Object obj) {
        if (!StormCellWarmingConfig.isEnabled()) {
            return false;
        }
        return StormCellWarmer.isWarmedAnimal(obj);
    }
}
