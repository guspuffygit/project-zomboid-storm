package io.pzstorm.storm.advice.serverlosruninner;

import io.pzstorm.storm.los.StormServerLos;
import net.bytebuddy.asm.Advice;

/**
 * Replaces the body of {@code ServerLOS$LOSThread.runInner} with the Storm LOS batch dispatcher.
 * {@link StormServerLos#runInnerParallel(Object)} always returns {@code true} (skipping vanilla):
 * at {@code threads == 1} it runs the batch single-threaded on slot 0, and at {@code threads >= 2}
 * it fans the scans across that many slots.
 */
public class ServerLOSRunInnerAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object losThread) {
        return StormServerLos.runInnerParallel(losThread);
    }
}
