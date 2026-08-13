package io.pzstorm.storm.advice.cutawayvisit;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.iso.fboRenderChunk.FBORenderCutaways.cutawayVisit}.
 *
 * <p>Routes the call through {@link CutawayVisitFastPath#visit}: a {@code true} verdict means the
 * fast body ran and the vanilla body is skipped; {@code false} (failure latch) leaves the vanilla
 * body to run untouched. All mechanism and the parity argument live on the helper — this class
 * stays free of game types so inlining it into the target never forces a class load.
 */
public class CutawayVisitAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.This Object self,
            @Advice.Argument(0) Object poiSquare,
            @Advice.Argument(1) long currentTimeMillis) {
        return CutawayVisitFastPath.visit(self, poiSquare, currentTimeMillis);
    }
}
