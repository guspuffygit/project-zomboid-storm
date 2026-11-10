package io.pzstorm.storm.advice.lazytiles;

import net.bytebuddy.asm.Advice;
import zombie.core.textures.Texture;

public class LazyPageSpriteStateAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) Texture tex) {
        if (tex != null && !tex.isReady()) {
            LazyTilePages.request(tex);
        }
    }
}
