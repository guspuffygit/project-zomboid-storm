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
 * <p>Also replaces the client-side state queries {@code isDone(byte)} and {@code isRejected(byte)}
 * (see {@link io.pzstorm.storm.advice.actionmanager.ActionStateQuery}). Vanilla prefixes both with
 * {@code !actions.isEmpty()}, so once the 30-minute client timeout removes a stalled action neither
 * query can ever return true and {@code LuaTimedActionNew.update} polls forever. With the patch an
 * absent id reads as rejected, so the action force-stops and the player's queue drains.
 *
 * <p>This is a client-side bytecode patch. The stall lives in Java ({@code ActionManager} and
 * {@code LuaTimedActionNew}), so no Lua tier can reach it. Every advice suppresses its own failures
 * and falls back to the vanilla body; re-validate {@code isDone}/{@code isRejected} on each game
 * update.
 *
 * <p><b>Reflection:</b> the {@code id}, {@code playerId}, {@code state}, and {@code stop()} members
 * live in the package-private {@code Action} base class and are accessed via cached reflection
 * handles (same pattern as {@link NetTimedActionPacketPatch}).
 */
public class ActionManagerPatch extends StormClassTransformer {

    public ActionManagerPatch() {
        super("zombie.core.ActionManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        String pkg = "io.pzstorm.storm.advice.actionmanager.";
        return builder.visit(
                        Advice.to(typePool.describe(pkg + "StopAdvice").resolve(), locator)
                                .on(
                                        ElementMatchers.named("stop")
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(typePool.describe(pkg + "IsDoneAdvice").resolve(), locator)
                                .on(
                                        ElementMatchers.named("isDone")
                                                .and(ElementMatchers.takesArguments(byte.class))))
                .visit(
                        Advice.to(typePool.describe(pkg + "IsRejectedAdvice").resolve(), locator)
                                .on(
                                        ElementMatchers.named("isRejected")
                                                .and(ElementMatchers.takesArguments(byte.class))));
    }
}
