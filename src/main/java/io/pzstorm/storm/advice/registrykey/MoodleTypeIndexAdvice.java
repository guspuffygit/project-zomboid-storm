package io.pzstorm.storm.advice.registrykey;

import io.pzstorm.storm.scripting.StormRegistryKeyIndex;
import net.bytebuddy.asm.Advice;

/** Constructor-exit advice for {@code MoodleType}: assigns the key's dense array index. */
public class MoodleTypeIndexAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.FieldValue(value = "stormIndex", readOnly = false) int index) {
        index = StormRegistryKeyIndex.nextMoodle();
    }
}
