package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Debug logging patch for {@code ActionStateContainer}. Instruments key methods to trace the full
 * state stack around child state insertion failures (e.g. "turning" not supported by parent
 * "turningmovement180"). This helps diagnose the evaluation-order race condition where {@code
 * evaluateCurrentStateTransitions} tries to insert a substate before {@code
 * evaluateSubStateTransitions} has removed a stale conflicting child.
 *
 * <p>Advice classes are standalone files referenced via {@code typePool.describe().resolve()} and
 * {@code locator}. This avoids the {@link ClassCircularityError} that previously required all
 * advice signatures to use {@code Object} — the advice bytecode is read without loading, so the
 * standalone classes can safely reference {@code ActionStateContainer}, {@code ActionContext}, and
 * {@code ActionState} directly.
 */
public class ActionStateContainerPatch extends StormClassTransformer {

    public ActionStateContainerPatch() {
        super("zombie.characters.action.ActionStateContainer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        String pkg = "io.pzstorm.storm.advice.actionstate.";
        return builder.visit(
                        Advice.to(
                                        typePool.describe(pkg + "EvaluateCurrentStateAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("evaluateCurrentState")
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(
                                        typePool.describe(pkg + "TryInsertChildStateAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("tryInsertChildState")
                                                .and(ElementMatchers.takesArguments(2))))
                .visit(
                        Advice.to(
                                        typePool.describe(pkg + "SetCurrentStateAdvice").resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("setCurrentState")
                                                .and(ElementMatchers.takesArguments(2))
                                                .and(ElementMatchers.returns(boolean.class))))
                .visit(
                        Advice.to(
                                        typePool.describe(pkg + "RemoveChildStateAtAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("removeChildStateAt")
                                                .and(ElementMatchers.takesArguments(1))));
    }
}
