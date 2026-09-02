package io.pzstorm.storm.advice.registrykey;

import io.pzstorm.storm.scripting.StormRegistryKeyIndex;
import net.bytebuddy.asm.Advice;

/** Constructor-exit advice for {@code CharacterStat}: assigns the key's dense array index. */
public class CharacterStatIndexAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.FieldValue(value = "stormIndex", readOnly = false) int index) {
        index = StormRegistryKeyIndex.nextStat();
    }
}
