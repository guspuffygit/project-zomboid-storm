package io.pzstorm.storm.advice.zombieculldisable;

import net.bytebuddy.asm.Advice;

/**
 * Skips the body of {@code ZombieCountOptimiser.incrementZombie(IsoZombie)} so no live zombie is
 * ever queued for culling. The owning transformer is registration-gated server-only and behind
 * {@code -Dstorm.disableZombieCull=true}, so whenever this advice is woven we always want to skip.
 */
public class ZombieCullDisableAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return true;
    }
}
