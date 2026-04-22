package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes a vanilla bug in {@code ActionManager.stop(Action)} / {@code ActionManager.remove(byte,
 * boolean)} where removal filters by byte id alone, causing one player's cancel to also remove
 * every other player's action that shares the same byte id.
 *
 * <p>The fix replaces the server-side {@code stop(Action)} with logic that filters by <em>both</em>
 * byte id and player online id, so each player's action lifecycle is independent.
 *
 * <p><b>Reflection:</b> the {@code id}, {@code playerId}, and {@code stop()} members live in the
 * package-private {@code Action} base class and are accessed via cached reflection handles (same
 * pattern as {@link NetTimedActionPacketPatch}).
 */
public class ActionManagerPatch extends StormClassTransformer {

    public ActionManagerPatch() {
        super("zombie.core.ActionManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(
                                                "io.pzstorm.storm.advice.actionmanager.StopAdvice")
                                        .resolve(),
                                locator)
                        .on(ElementMatchers.named("stop").and(ElementMatchers.takesArguments(1))));
    }
}
