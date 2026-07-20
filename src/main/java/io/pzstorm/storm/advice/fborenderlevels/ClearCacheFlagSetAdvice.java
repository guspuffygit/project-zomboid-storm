package io.pzstorm.storm.advice.fborenderlevels;

import net.bytebuddy.asm.Advice;

/**
 * Advice for the no-arg {@code zombie.iso.fboRenderChunk.FBORenderLevels.clearCache()}, which nulls
 * every zoom slot's render chunk directly (without going through {@code freeFBO}). Sets {@code
 * storm$noFBOs} so {@link FreeFBOsForLevelSkipAdvice} can skip subsequent {@code freeFBOsForLevel}
 * calls on a single field read.
 */
public class ClearCacheFlagSetAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.FieldValue(value = "storm$noFBOs", readOnly = false) boolean noFBOs) {
        noFBOs = true;
    }
}
