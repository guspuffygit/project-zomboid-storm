package io.pzstorm.storm.advice.floorflags;

import io.pzstorm.storm.iso.StormFloorFlags;
import net.bytebuddy.asm.Advice;

/**
 * Replaces the body of {@code IsoGridSquare.hasSand()} with {@link
 * StormFloorFlags#hasSand(Object)}.
 */
public class HasSandAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return true;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.This Object self, @Advice.Return(readOnly = false) boolean ret) {
        ret = StormFloorFlags.hasSand(self);
    }
}
