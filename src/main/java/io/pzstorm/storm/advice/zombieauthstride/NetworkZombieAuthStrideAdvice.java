package io.pzstorm.storm.advice.zombieauthstride;

import io.pzstorm.storm.patch.performance.ZombieAuthTickInterval;
import net.bytebuddy.asm.Advice;
import zombie.characters.IsoZombie;

public class NetworkZombieAuthStrideAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.Argument(0) IsoZombie zombie) {
        return ZombieAuthTickInterval.shouldSkipThisTick(zombie);
    }
}
