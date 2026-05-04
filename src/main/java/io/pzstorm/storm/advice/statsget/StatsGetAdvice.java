package io.pzstorm.storm.advice.statsget;

import java.util.Map;
import net.bytebuddy.asm.Advice;
import zombie.characters.CharacterStat;

/**
 * Advice for {@code zombie.characters.Stats.get(CharacterStat)}.
 *
 * <p>The vanilla body is {@code return this.stats.getOrDefault(stat, stat.getDefaultValue())}.
 * Java evaluates the second argument eagerly, autoboxing the primitive {@code float} into a {@link
 * Float} on every call, even when the map already contains an entry for {@code stat}. JFR analysis
 * showed this single autobox dominating main-thread allocation (~92% of pressure), driven by
 * per-tick LOS calls into {@code IsoGameCharacter.getDetectionRange} for every remote player.
 *
 * <p>The advice replaces the body with a {@code Map.get} + null-check, so the default is only
 * computed (and never boxed) on a map miss. The storage path ({@code Stats.set}) still boxes once
 * per write, but writes are several orders of magnitude rarer than reads.
 *
 * <p>Pattern: enter advice always returns {@code true} to skip the original body; exit advice
 * writes the computed value via {@code @Advice.Return(readOnly = false)}. Same shape as {@link
 * io.pzstorm.storm.patch.rendering.UIWorldMapV1Patch.GetOptionByIndexAdvice}.
 */
public class StatsGetAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return true;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Argument(0) CharacterStat stat,
            @Advice.FieldValue("stats") Map<CharacterStat, Float> stats,
            @Advice.Return(readOnly = false) float ret) {
        Float v = stats.get(stat);
        if (v != null) {
            ret = v.floatValue();
        } else {
            ret = stat.getDefaultValue();
        }
    }
}
