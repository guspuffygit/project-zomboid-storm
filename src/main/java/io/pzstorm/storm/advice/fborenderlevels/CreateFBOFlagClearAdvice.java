package io.pzstorm.storm.advice.fborenderlevels;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.iso.fboRenderChunk.FBORenderLevels.createFBOForLevel(int, float)} — the
 * single site that installs a render chunk into a zoom slot. Clears {@code storm$noFBOs} so {@link
 * FreeFBOsForLevelSkipAdvice} stops skipping {@code freeFBOsForLevel} for this chunk.
 */
public class CreateFBOFlagClearAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.FieldValue(value = "storm$noFBOs", readOnly = false) boolean noFBOs) {
        noFBOs = false;
    }
}
