package io.pzstorm.storm.advice.zombieauthscan;

import io.pzstorm.storm.zombie.StormZombieAuthScan;
import net.bytebuddy.asm.Advice;

/**
 * Brackets the private {@code NetworkZombiePacker.updateAuth()} pass (the per-tick loop over the
 * whole zombie list) with {@link StormZombieAuthScan#beginPass()} / {@link
 * StormZombieAuthScan#endPass()}. The snapshot built at pass entry is what makes the per-zombie
 * fast path in {@code NetworkZombieManagerAuthScanAdvice} valid; outside this bracket that advice
 * always falls through to vanilla (e.g. {@code clearTargetAuth} on player disconnect).
 */
public class NetworkZombiePackerAuthPassAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        StormZombieAuthScan.beginPass();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        StormZombieAuthScan.endPass();
    }
}
