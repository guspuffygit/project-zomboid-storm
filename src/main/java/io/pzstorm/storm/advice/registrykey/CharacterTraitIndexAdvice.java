package io.pzstorm.storm.advice.registrykey;

import io.pzstorm.storm.scripting.StormRegistryKeyIndex;
import net.bytebuddy.asm.Advice;

/** Constructor-exit advice for {@code CharacterTrait}: assigns the key's dense array index. */
public class CharacterTraitIndexAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.FieldValue(value = "stormIndex", readOnly = false) int index) {
        index = StormRegistryKeyIndex.nextTrait();
    }
}
