package io.pzstorm.storm.advice.isogridsquareremoveglassattachments;

import io.pzstorm.storm.patch.fixes.GlassAttachmentRemovalGuard;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the entry of {@code IsoGridSquare.removeGlassAttachments(IsoWindow)}. Runs {@link
 * GlassAttachmentRemovalGuard#removeGlassAttachments(Object, Object)} and returns {@code true} so
 * the vanilla body, whose {@code n--} after a failed removal spins the main thread forever, is
 * skipped. If the helper throws, the exception propagates as it would from the vanilla body.
 */
public class IsoGridSquareRemoveGlassAttachmentsAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object square, @Advice.Argument(0) Object window) {
        GlassAttachmentRemovalGuard.removeGlassAttachments(square, window);
        return true;
    }
}
