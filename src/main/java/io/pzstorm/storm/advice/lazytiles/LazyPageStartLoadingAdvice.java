package io.pzstorm.storm.advice.lazytiles;

import net.bytebuddy.asm.Advice;
import zombie.asset.Asset;

public class LazyPageStartLoadingAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.Argument(0) Asset asset) {
        return LazyTilePages.defer(asset);
    }
}
