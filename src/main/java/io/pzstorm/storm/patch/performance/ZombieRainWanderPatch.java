package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Lets a server take back the cost of rain. See {@link ZombieRainWanderInterval} for the behaviour
 * analysis; configured via the {@code Storm.ZombieRainWanderPercent} sandbox option, which defaults
 * to vanilla.
 *
 * <p>Targets {@code ZombieIdleState.pickRandomWanderInterval()}, the private zero-argument method
 * that both {@code enter()} and {@code execute()} call to decide how long an idle zombie stands
 * still, with an exit advice that may lengthen the returned interval while it is raining. Nothing
 * else in the state is touched: the wander destination, the {@code pathToLocation} call, the
 * indoor/useless gates and the {@code allowRepathDelay} are all vanilla's, and only the interval
 * between picks changes.
 *
 * <p>The target method is private, so a build that renames or inlines it would leave the advice
 * silently unattached. {@link #dynamicType} therefore asserts the shape and fails loudly at
 * registration instead, the same way {@code ServerMapPostUpdateBudgetPatch} does.
 */
public class ZombieRainWanderPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.ai.states.ZombieIdleState";
    private static final String PKG = "io.pzstorm.storm.advice.zombierainwander.";
    static final String DECIDER = "pickRandomWanderInterval";

    public ZombieRainWanderPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(ElementMatchers.named(DECIDER).and(ElementMatchers.takesArguments(0)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "ZombieRainWanderPatch: ZombieIdleState no longer declares "
                            + DECIDER
                            + "() - the rain-to-wander coupling has moved and this patch must be"
                            + " re-read against the new engine build before it is trusted");
        }
        return builder.visit(
                Advice.to(typePool.describe(PKG + "WanderIntervalAdvice").resolve(), locator)
                        .on(ElementMatchers.named(DECIDER).and(ElementMatchers.takesArguments(0))));
    }
}
