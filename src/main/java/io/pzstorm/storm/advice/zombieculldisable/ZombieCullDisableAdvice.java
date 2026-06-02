package io.pzstorm.storm.advice.zombieculldisable;

import io.pzstorm.storm.patch.performance.StormZombieCullConfig;
import net.bytebuddy.asm.Advice;

/**
 * Skips the body of {@code ZombieCountOptimiser.incrementZombie(IsoZombie)} when the runtime flag
 * {@link StormZombieCullConfig#isDisabled()} is {@code true}, so no live zombie is queued for
 * culling. When the flag is {@code false}, the method runs as vanilla.
 *
 * <p>The owning transformer is registration-gated server-only, so this advice is only ever woven
 * into the dedicated server JVM. The flag is checked on every entry to {@code incrementZombie},
 * making runtime toggling effective on the next call.
 */
public class ZombieCullDisableAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return StormZombieCullConfig.isDisabled();
    }
}
