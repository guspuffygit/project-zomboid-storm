package io.pzstorm.storm.advice.statsget;

import io.pzstorm.storm.characters.StormIndexedMaps;
import net.bytebuddy.asm.Advice;
import zombie.characters.CharacterStat;

/**
 * Advice for {@code zombie.characters.Stats.get(CharacterStat)}.
 *
 * <p>The vanilla body is {@code return this.stats.getOrDefault(stat, stat.getDefaultValue())}. Java
 * evaluates the second argument eagerly, autoboxing the primitive {@code float} into a {@link
 * Float} on every call, even when the map already contains an entry for {@code stat}. JFR analysis
 * showed this single autobox dominating main-thread allocation (~92% of pressure), driven by
 * per-tick LOS calls into {@code IsoGameCharacter.getDetectionRange} for every remote player.
 *
 * <p>The advice replaces the body with {@link StormIndexedMaps#getFloat}: one array read on the
 * character's {@link io.pzstorm.storm.characters.StormIndexedMap} (the {@code stats} field is
 * redirected there by {@code StatsGetPatch}), unboxed, with the default only used on a miss. The
 * storage path ({@code Stats.set}) still boxes once per write, as vanilla does.
 *
 * <p>Pattern: enter advice always returns {@code true} to skip the original body; exit advice
 * writes the computed value via {@code @Advice.Return(readOnly = false)}.
 */
public class StatsGetAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return true;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.This Object self,
            @Advice.Argument(0) CharacterStat stat,
            @Advice.Return(readOnly = false) float ret) {
        ret = StormIndexedMaps.getFloat(self, stat, stat.getDefaultValue());
    }
}
