package io.pzstorm.storm.advice.virtualanimalstride;

import io.pzstorm.storm.patch.performance.VirtualAnimalTickInterval;
import net.bytebuddy.asm.Advice;

public class AnimalZonesVirtualAnimalStrideAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return VirtualAnimalTickInterval.shouldSkipThisTick();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        // Idempotent: restores perObjectMultiplier only when onEnter raised it, so the
        // skipped-body case (exit still runs) and the vanilla stride-1 case are no-ops.
        VirtualAnimalTickInterval.endCompensatedRun();
    }
}
