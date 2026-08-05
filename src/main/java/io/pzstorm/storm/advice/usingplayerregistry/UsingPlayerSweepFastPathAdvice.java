package io.pzstorm.storm.advice.usingplayerregistry;

import io.pzstorm.storm.entity.UsingPlayerRegistry;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Routes {@code UsingPlayerUpdateSystem.update()} through {@link UsingPlayerRegistry#runSweep()}: a
 * {@code true} verdict means the registry-backed sweep ran and the vanilla full-bucket scan is
 * skipped; {@code false} (client JVM guard, kill switch, or failure latch) leaves the vanilla body
 * to run untouched.
 *
 * <p>Registered <em>before</em> the stopwatch-only {@code UsingPlayerUpdatePatch} in {@code
 * StormClassTransformers}, so the timing advice wraps this one and {@code
 * pz_using_player_update_call_duration_seconds} keeps measuring the full call on both paths.
 */
public class UsingPlayerSweepFastPathAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        if (!GameServer.server) {
            return false;
        }
        return UsingPlayerRegistry.runSweep();
    }
}
