package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Weaves {@code CharacterVariableResolveAdvice} onto the private static {@code
 * CharacterVariableCondition.resolveValue(Object, IAnimationVariableSource)} so action-condition
 * operands that reference an animation variable are resolved from the slot's typed value rather
 * than through {@code getValueString()} plus the vanilla string parser. Pairs with {@link
 * CharacterVariableLookupAccessorPatch}. Server-only by registration gate. Fails loud if the method
 * signature changes.
 */
public class CharacterVariableResolveTypedPatch extends StormClassTransformer {

    private static final String TARGET =
            "zombie.characters.action.conditions.CharacterVariableCondition";

    public CharacterVariableResolveTypedPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        if (typePool.describe(TARGET)
                .resolve()
                .getDeclaredMethods()
                .filter(
                        ElementMatchers.named("resolveValue")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(2)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "CharacterVariableResolveTypedPatch: CharacterVariableCondition no longer"
                            + " declares static resolveValue(Object, IAnimationVariableSource) —"
                            + " re-verify against the current game source.");
        }
        return builder.visit(
                Advice.to(
                                typePool.describe(
                                                "io.pzstorm.storm.advice.actioncondition"
                                                        + ".CharacterVariableResolveAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("resolveValue")
                                        .and(ElementMatchers.isStatic())
                                        .and(ElementMatchers.takesArguments(2))));
    }
}
