package io.pzstorm.storm.advice.profilergate;

import io.pzstorm.storm.profiling.StormGameProfilerGate;
import net.bytebuddy.asm.Advice;

/**
 * Static {@code GameProfiler.isRunning()}: when no thread's profiler is on, skips the {@code
 * ThreadLocal} lookup and returns the default {@code false} — exactly what the lookup would yield.
 */
public class GameProfilerIsRunningGateAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return StormGameProfilerGate.running == 0;
    }
}
