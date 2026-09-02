package io.pzstorm.storm.advice.profilergate;

import io.pzstorm.storm.profiling.StormGameProfilerGate;
import net.bytebuddy.asm.Advice;

/**
 * {@code AbstractPerformanceProfileProbe.start()}: skipped when the body would be a no-op. With
 * every profiler off, vanilla's only effect is {@code isProfilerRunning = false} (after the thread
 * check, and only if {@code isRunning} is false — otherwise it throws), so the skip is exact when
 * {@code isRunning} and {@code isProfilerRunning} are both already false.
 */
public class ProbeStartGateAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.FieldValue("isRunning") boolean isRunning,
            @Advice.FieldValue("isProfilerRunning") boolean isProfilerRunning) {
        return !isRunning && !isProfilerRunning && StormGameProfilerGate.running == 0;
    }
}
