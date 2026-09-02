package io.pzstorm.storm.advice.itemtag;

import io.pzstorm.storm.scripting.StormItemTagIndex;
import net.bytebuddy.asm.Advice;

/** Constructor-exit advice for {@code ItemTag}: assigns the tag's dense bit index. */
public class ItemTagIndexAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.FieldValue(value = "stormIndex", readOnly = false) int index) {
        index = StormItemTagIndex.next();
    }
}
