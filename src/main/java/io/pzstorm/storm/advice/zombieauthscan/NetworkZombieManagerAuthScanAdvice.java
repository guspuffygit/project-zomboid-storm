package io.pzstorm.storm.advice.zombieauthscan;

import io.pzstorm.storm.zombie.StormZombieAuthScan;
import net.bytebuddy.asm.Advice;
import zombie.characters.IsoZombie;

/**
 * Routes {@code NetworkZombieManager.updateAuth(IsoZombie)} through {@link
 * StormZombieAuthScan#updateAuthFast}: a {@code true} verdict means the snapshot-backed scan
 * handled the zombie and the vanilla body is skipped; {@code false} (no active packer pass or
 * failure latch) leaves the vanilla body to run untouched.
 *
 * <p>Weave order matters: this patch is registered after {@code NetworkZombieManagerAuthPatch} (so
 * the fast path also skips that advice's per-zombie native-histogram observation — itself a
 * measurable tick cost at 7,000 zombies/tick) and before {@code ZombieAuthStridePatch} (so the
 * stride check stays outermost and still thins off-phase unowned zombies first).
 */
public class NetworkZombieManagerAuthScanAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.This Object manager, @Advice.Argument(0) IsoZombie zombie) {
        return StormZombieAuthScan.updateAuthFast(manager, zombie);
    }
}
