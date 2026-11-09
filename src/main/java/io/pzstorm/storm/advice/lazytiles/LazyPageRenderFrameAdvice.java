package io.pzstorm.storm.advice.lazytiles;

import net.bytebuddy.asm.Advice;
import zombie.iso.IsoDirections;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSprite;

public class LazyPageRenderFrameAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.This IsoSprite self,
            @Advice.Argument(1) IsoObject obj,
            @Advice.Argument(2) IsoDirections dir,
            @Advice.Argument(3) int frame) {
        return LazyTilePages.skipFrame(self, obj, dir, frame);
    }
}
