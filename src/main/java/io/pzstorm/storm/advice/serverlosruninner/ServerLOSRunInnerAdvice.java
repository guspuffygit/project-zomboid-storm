package io.pzstorm.storm.advice.serverlosruninner;

import io.pzstorm.storm.los.StormServerLos;
import net.bytebuddy.asm.Advice;

/**
 * Replaces the body of {@code ServerLOS$LOSThread.runInner} with the parallel batch dispatcher when
 * the configured worker count is &ge; 2. At {@code threads == 1} the helper returns {@code false},
 * the vanilla {@code runInner} runs unchanged, and the parallel path is never entered.
 */
public class ServerLOSRunInnerAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object losThread) {
        return StormServerLos.runInnerParallel(losThread);
    }
}
