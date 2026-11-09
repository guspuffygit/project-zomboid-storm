package io.pzstorm.storm.advice.lazytiles;

import net.bytebuddy.asm.Advice;
import zombie.core.textures.TextureID;

public class LazyPageVboTextureAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) TextureID id) {
        if (id != null && id.isEmpty()) {
            LazyTilePages.requestPage(id);
        }
    }
}
