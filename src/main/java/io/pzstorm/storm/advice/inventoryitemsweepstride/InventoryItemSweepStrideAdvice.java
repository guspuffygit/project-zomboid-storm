package io.pzstorm.storm.advice.inventoryitemsweepstride;

import io.pzstorm.storm.patch.performance.InventoryItemSweepTickInterval;
import net.bytebuddy.asm.Advice;

public class InventoryItemSweepStrideAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return InventoryItemSweepTickInterval.shouldSkipThisTick();
    }
}
