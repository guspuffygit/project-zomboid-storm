package io.pzstorm.storm.advice.hutchdirtrate;

import io.pzstorm.storm.patch.fixes.HutchDirtRateFix;
import net.bytebuddy.asm.Advice;

/**
 * Inlined around {@code zombie.iso.objects.IsoHutch.update()}. Entry captures the pre-update dirt
 * state, exit reverts the vanilla per-tick dirt rolls and applies Storm's game-time accrual — see
 * {@link io.pzstorm.storm.patch.fixes.HutchDirtRateFixPatch} for the rationale.
 *
 * <p>{@code @Advice.This} is typed {@code Object} (not {@code IsoHutch}) so the inlined call site
 * does not encode a checkcast against a game class — a typed parameter would let javac elide the
 * cast and the JVM verifier would resolve the class at patch registration, before the transformer
 * is in place to apply itself. See the {@code feedback_elided_cast_load} memory.
 *
 * <p>No {@code onThrowable}: if the vanilla body throws, the exit advice is skipped, the scratch
 * state is simply overwritten by the next hutch's entry, and dirt for that tick stays whatever
 * vanilla left it at.
 */
public class HutchDirtRateAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This Object hutch) {
        HutchDirtRateFix.beforeUpdate(hutch);
    }

    @Advice.OnMethodExit
    public static void onExit(@Advice.This Object hutch) {
        HutchDirtRateFix.afterUpdate(hutch);
    }
}
