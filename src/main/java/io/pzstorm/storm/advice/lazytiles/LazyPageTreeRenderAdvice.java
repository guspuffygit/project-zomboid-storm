package io.pzstorm.storm.advice.lazytiles;

import net.bytebuddy.asm.Advice;
import zombie.iso.objects.IsoTree;

public class LazyPageTreeRenderAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This IsoTree self) {
        return LazyTilePages.skipTree(self);
    }
}
