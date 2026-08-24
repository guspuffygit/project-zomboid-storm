package io.pzstorm.storm.advice.serversoundindex;

import io.pzstorm.storm.sound.StormRepeatingSoundCoalescer;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Routes {@code WorldSoundManager.getStressFromSounds} through {@link
 * StormRepeatingSoundCoalescer#stressFromSounds} on the server: the same additive loop, but each
 * coalesced repeating slot is weighted by the copy count vanilla would have alive, so player stress
 * accumulation is unchanged by coalescing. A {@code null} verdict (client JVM, coalescing disabled
 * or failed — in which case no coalesced sounds exist and every weight is 1) leaves the vanilla
 * body to run untouched.
 */
public class WorldSoundManagerGetStressAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object onEnter(
            @Advice.This Object manager,
            @Advice.Argument(0) int x,
            @Advice.Argument(1) int y,
            @Advice.Argument(2) int z) {
        if (!GameServer.server) {
            return null;
        }
        return StormRepeatingSoundCoalescer.stressFromSounds(manager, x, y, z);
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter Object computed, @Advice.Return(readOnly = false) float result) {
        if (computed != null) {
            result = (Float) computed;
        }
    }
}
