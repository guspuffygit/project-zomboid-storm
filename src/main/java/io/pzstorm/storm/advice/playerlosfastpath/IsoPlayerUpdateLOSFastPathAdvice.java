package io.pzstorm.storm.advice.playerlosfastpath;

import io.pzstorm.storm.los.StormPlayerLos;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Routes {@code IsoPlayer.updateLOS()} through {@link StormPlayerLos#runOptimized}: a {@code true}
 * verdict means the server-stripped body ran and the vanilla body is skipped; {@code false} (client
 * JVM guard, kill switch, fast-path failure latch, or no cached {@code PlayerData} yet) leaves the
 * vanilla body to run untouched.
 *
 * <p>Registered <em>before</em> the stopwatch-only {@code IsoPlayerUpdateLOSPatch} in {@code
 * StormClassTransformers}, so the timing advice wraps this one and {@code
 * pz_player_update_los_call_duration_seconds} keeps measuring the full call on both paths.
 */
public class IsoPlayerUpdateLOSFastPathAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object player) {
        if (!GameServer.server) {
            return false;
        }
        return StormPlayerLos.runOptimized(player);
    }
}
