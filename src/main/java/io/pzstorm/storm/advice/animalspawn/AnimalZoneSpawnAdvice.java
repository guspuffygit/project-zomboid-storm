package io.pzstorm.storm.advice.animalspawn;

import io.pzstorm.storm.metrics.AnimalSpawnMetrics;
import net.bytebuddy.asm.Advice;

/**
 * Wraps {@code zombie.characters.animals.AnimalZones.spawnAnimalsOnZone(AnimalZone)} — the only
 * place vanilla creates wild animals from a migration zone.
 *
 * <p>The enter advice classifies the zone (already spawned / spawning disabled / non-{@code Follow}
 * action / eligible); the exit advice turns an eligible call into {@code spawned} or {@code
 * no_animals} depending on whether a virtual group was actually registered while it ran. Exit runs
 * on throw as well so the thread-local marker is always cleared.
 *
 * <p>{@code @Advice.Argument} is typed {@code Object} so the inlined call site does not encode a
 * checkcast against {@code AnimalZone} (see the {@code feedback_elided_cast_load} memory).
 */
public class AnimalZoneSpawnAdvice {

    @Advice.OnMethodEnter
    public static boolean onEnter(@Advice.Argument(0) Object zone) {
        return AnimalSpawnMetrics.beginZoneSpawn(zone);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter boolean eligible) {
        AnimalSpawnMetrics.endZoneSpawn(eligible);
    }
}
