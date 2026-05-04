package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.characters.CharacterStat;

/**
 * Replaces {@code zombie.characters.Stats.get(CharacterStat)} with a non-boxing implementation. See
 * {@link io.pzstorm.storm.advice.statsget.StatsGetAdvice} for the rationale.
 *
 * <p>Targets only the single-argument {@code float get(CharacterStat)} overload (not the unrelated
 * inherited {@code Object} accessors).
 */
public class StatsGetPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.statsget.";

    public StatsGetPatch() {
        super("zombie.characters.Stats");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "StatsGetAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("get")
                                        .and(ElementMatchers.takesArgument(0, CharacterStat.class))
                                        .and(ElementMatchers.returns(float.class))));
    }
}
