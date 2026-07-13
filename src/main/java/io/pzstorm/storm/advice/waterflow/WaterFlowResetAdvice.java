package io.pzstorm.storm.advice.waterflow;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.iso.IsoWaterFlow.Reset()} — drops the {@link WaterFlowGetFlowMemoAdvice}
 * cache when the flow points are cleared (map reload / new game), so a different map never serves
 * the previous map's flow vectors even within the TTL window.
 */
public class WaterFlowResetAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        WaterFlowGetFlowMemoAdvice.CACHE.clear();
    }
}
