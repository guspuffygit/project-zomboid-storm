package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}. This is a
 * deliberate, user-approved exception to the no-client-patches rule — do not use it as precedent,
 * and do not register it outside the experimental gate.
 *
 * <p>Replaces the {@code Event.trigger} callback loop with one that skips the per-callback profiler
 * label concatenation and the per-callback O(n) {@code contains} scan. See {@link
 * io.pzstorm.storm.advice.eventtrigger.EventTriggerFastPathAdvice} for the mechanism, profiling
 * data and the one documented semantic edge case.
 */
public class EventTriggerFastPathPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.eventtrigger.";

    public EventTriggerFastPathPatch() {
        super("zombie.Lua.Event");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "EventTriggerFastPathAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("trigger")
                                        .and(ElementMatchers.takesArguments(3))));
    }
}
