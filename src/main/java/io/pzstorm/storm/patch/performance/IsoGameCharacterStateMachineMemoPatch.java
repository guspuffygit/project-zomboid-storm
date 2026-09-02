package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Adds the {@code stormStateMachine} field (public {@code Object}) to {@code
 * zombie.characters.IsoGameCharacter} and weaves {@code StateMachineComponentMemoAdvice} into
 * {@code getStateMachineComponent()}, turning the hottest ECS component lookup into a field read
 * plus an ownership check. See the advice for the equivalence argument.
 *
 * <p>Covers every subclass — {@code IsoPlayer}, {@code IsoZombie}, {@code IsoAnimal}. Server-only
 * by registration gate ({@code StormEnv.isStormServer()}). Fails loud at weave time if the target
 * stops being a zero-argument method on {@code IsoGameCharacter}.
 */
public class IsoGameCharacterStateMachineMemoPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.characters.IsoGameCharacter";

    public IsoGameCharacterStateMachineMemoPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        if (typePool.describe(TARGET)
                .resolve()
                .getDeclaredMethods()
                .filter(
                        ElementMatchers.named("getStateMachineComponent")
                                .and(ElementMatchers.takesArguments(0)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "IsoGameCharacterStateMachineMemoPatch: IsoGameCharacter no longer declares"
                            + " getStateMachineComponent() — re-verify against the current game"
                            + " source.");
        }
        return builder.defineField("stormStateMachine", Object.class, Visibility.PUBLIC)
                .visit(
                        Advice.to(
                                        typePool.describe(
                                                        "io.pzstorm.storm.advice.ecsmemo"
                                                                + ".StateMachineComponentMemoAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("getStateMachineComponent")
                                                .and(ElementMatchers.takesArguments(0))));
    }
}
