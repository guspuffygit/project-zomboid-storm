package io.pzstorm.storm.advice.profilergate;

import io.pzstorm.storm.profiling.StormGameProfilerGate;
import net.bytebuddy.asm.Advice;

/**
 * On {@code GameProfiler.startFrame}/{@code endFrame} (the only writers of {@code isRunning}):
 * reports the before/after value to {@link StormGameProfilerGate}. Runs on the throwing paths too —
 * {@code endFrame} writes the flag in a {@code finally}.
 */
public class GameProfilerFrameTransitionAdvice {

    @Advice.OnMethodEnter
    public static boolean onEnter(@Advice.FieldValue("isRunning") boolean before) {
        return before;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Enter boolean before, @Advice.FieldValue("isRunning") boolean after) {
        StormGameProfilerGate.onTransition(before, after);
    }
}
