package io.pzstorm.storm.advice.entityindex;

import io.pzstorm.storm.entity.StormEntityIndex;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Exit hook on the single-arg {@code zombie.entity.util.Array.add(T)}: keeps the {@link
 * StormEntityIndex} in sync at the exact instant an entity is appended to a tracked array (the
 * global entity array or a bucket's). For every other {@code Array} instance in the JVM the helper
 * bails after one field read (the injected index slot is null), so the per-add overhead off tracked
 * arrays is negligible.
 */
public class EntityArrayAddAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.This Object array, @Advice.Argument(0) Object value) {
        if (!GameServer.server) {
            return;
        }
        StormEntityIndex.onArrayAdd(array, value);
    }
}
