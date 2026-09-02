package io.pzstorm.storm.advice.profilergate;

import net.bytebuddy.asm.Advice;

/**
 * {@code AbstractPerformanceProfileProbe.end()}: vanilla does nothing at all unless {@code
 * isProfilerRunning} is set (the thread check only guards that branch), so the skip needs no global
 * gate.
 */
public class ProbeEndGateAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.FieldValue("isProfilerRunning") boolean isProfilerRunning) {
        return !isProfilerRunning;
    }
}
