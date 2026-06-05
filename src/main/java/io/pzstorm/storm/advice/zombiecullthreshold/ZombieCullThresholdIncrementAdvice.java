package io.pzstorm.storm.advice.zombiecullthreshold;

import io.pzstorm.storm.patch.performance.StormZombieCullConfig;
import java.util.ArrayList;
import net.bytebuddy.asm.Advice;

/**
 * Fixes vanilla's missing decrement of {@code zombieCountForDelete} in {@code
 * ZombieCountOptimiser.incrementZombie}. Vanilla checks {@code zombieCountForDelete > 0} as a gate
 * but never decrements it, so every zombie in the loop has a chance to be queued — the gate never
 * closes mid-frame and culling overshoots far past the threshold.
 *
 * <p>This advice captures {@code zombiesForDelete.size()} on entry and decrements {@code
 * zombieCountForDelete} on exit when vanilla actually added a zombie to the delete list, so the
 * gate closes after exactly {@code excess} queues per frame.
 *
 * <p>Gated on {@link StormZombieCullConfig#getThreshold()} being a positive value, so the fix is
 * opt-in and only kicks in alongside {@code ZombieCullThresholdStartAdvice}.
 */
public class ZombieCullThresholdIncrementAdvice {

    @Advice.OnMethodEnter
    public static int onEnter(@Advice.FieldValue("zombiesForDelete") ArrayList list) {
        return list.size();
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int sizeBefore,
            @Advice.FieldValue("zombiesForDelete") ArrayList list,
            @Advice.FieldValue(value = "zombieCountForDelete", readOnly = false) int count) {
        if (StormZombieCullConfig.getThreshold() > 0 && list.size() > sizeBefore && count > 0) {
            count--;
        }
    }
}
