package io.pzstorm.storm.advice.zombieculldisable;

import io.pzstorm.storm.patch.performance.StormZombieCullConfig;
import net.bytebuddy.asm.Advice;

/**
 * Skips the body of {@code ZombieCountOptimiser.incrementZombie(IsoZombie)} when {@link
 * StormZombieCullConfig#getThreshold()} is {@code 0}, so no live zombie is queued for culling. When
 * the threshold is {@code > 0}, the method runs as vanilla (with the threshold-decrement fix from
 * {@code ZombieCullThresholdIncrementAdvice} layered on top).
 *
 * <p>The owning transformer is registration-gated server-only, so this advice is only ever woven
 * into the dedicated server JVM. The threshold is checked on every entry to {@code incrementZombie}
 * (volatile read), making sandbox / HTTP toggling effective on the next call.
 */
public class ZombieCullDisableAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return StormZombieCullConfig.getThreshold() == 0;
    }
}
