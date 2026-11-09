package io.pzstorm.storm.advice.lazytiles;

import net.bytebuddy.asm.Advice;
import zombie.iso.IsoChunk;

public class LazyPageChunkLoadAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.This IsoChunk self) {
        LazyTilePages.requestChunk(self);
    }
}
