package io.pzstorm.storm.advice.movingobjectschedulerbucketadd;

import io.pzstorm.storm.patch.performance.StormCellWarmer;
import net.bytebuddy.asm.Advice;

/**
 * Skips the {@code MovingObjectUpdateSchedulerUpdateBucket.add(IsoMovingObject)} body when the
 * argument is an animal sitting inside a warmed cell (including cells still draining after a live
 * disable — the stash is only released when the cell is evicted or rewarmed). Returning {@code
 * true} suppresses the body; returning {@code false} runs vanilla. The downstream {@code update} /
 * {@code postupdate} loops iterate the buckets, so a warm animal that's never bucketed is fully
 * excluded from per-tick {@code IsoAnimal.update()} work.
 */
public class MovingObjectSchedulerBucketAddAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.Argument(0) Object obj) {
        return StormCellWarmer.isWarmedAnimal(obj);
    }
}
