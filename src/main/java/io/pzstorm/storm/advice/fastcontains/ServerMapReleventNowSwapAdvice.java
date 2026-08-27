package io.pzstorm.storm.advice.fastcontains;

import io.pzstorm.storm.util.StormFastContainsList;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Constructor-exit advice for {@code ServerMap}: replaces the freshly initialized {@code
 * releventNow} field with {@link StormFastContainsList} so the per-cell {@code
 * releventNow.contains(cell)} probe in {@code shouldBeLoaded} (run for every server cell every
 * update pass) resolves in O(1). The field is non-final, so no modifier change is needed.
 */
public class ServerMapReleventNowSwapAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.FieldValue(
                            value = "releventNow",
                            readOnly = false,
                            typing = Assigner.Typing.DYNAMIC)
                    Object releventNow) {
        releventNow = StormFastContainsList.copyOf(releventNow);
    }
}
