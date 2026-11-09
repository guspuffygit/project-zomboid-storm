package io.pzstorm.storm.advice.lazytiles;

import net.bytebuddy.asm.Advice;

public class LazyPageDrainAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        LazyTilePages.drain();
    }
}
