package io.pzstorm.storm.advice.lazytiles;

import net.bytebuddy.asm.Advice;
import zombie.core.textures.Texture;

public class LazyPageBindAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This Texture self) {
        if (!self.isReady()) {
            LazyTilePages.request(self);
        }
    }
}
