package io.pzstorm.storm.patch.performance;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.characters.CharacterStat;

/**
 * {@link IndexedMapFieldPatch} for {@code zombie.characters.Stats.stats}, plus a replacement body
 * for {@code float get(CharacterStat)} that reads the indexed slot directly and never boxes (see
 * {@link io.pzstorm.storm.advice.statsget.StatsGetAdvice}). The two live in one transform so the
 * advice can never run against a class whose field reads were not redirected.
 *
 * <p>Targets only the single-argument {@code float get(CharacterStat)} overload (not the unrelated
 * inherited {@code Object} accessors).
 */
public class StatsGetPatch extends IndexedMapFieldPatch {

    private static final String PKG = "io.pzstorm.storm.advice.statsget.";

    public StatsGetPatch() {
        super("zombie.characters.Stats", "stats");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return super.dynamicType(locator, typePool, builder)
                .visit(
                        Advice.to(typePool.describe(PKG + "StatsGetAdvice").resolve(), locator)
                                .on(
                                        ElementMatchers.named("get")
                                                .and(
                                                        ElementMatchers.takesArgument(
                                                                0, CharacterStat.class))
                                                .and(ElementMatchers.returns(float.class))));
    }
}
