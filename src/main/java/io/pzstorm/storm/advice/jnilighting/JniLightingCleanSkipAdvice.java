package io.pzstorm.storm.advice.jnilighting;

import net.bytebuddy.asm.Advice;
import zombie.iso.IsoGridSquare;
import zombie.iso.LightingJNI;

/**
 * Advice for the private {@code zombie.iso.LightingJNI$JNILighting.update()}.
 *
 * <p>Vanilla only short-circuits squares that were <em>processed</em> in the current lighting pass
 * ({@code updateTick == updateCounter}). A square whose {@code getSquareDirty} check came back
 * clean records nothing, so every subsequent {@code update()} call re-crosses JNI to ask the same
 * question until the pass counter advances — for the overwhelmingly-clean majority of visible
 * squares that is one native call per square per frame. Live-client sampling in downtown Louisville
 * attributed ~4% of MainThread (and ~8% of hitch-frame time) to the {@code getSquareDirty}/{@code
 * getSquareLighting} cluster.
 *
 * <p>The advice performs the dirty check itself once per pass and records the verdict in {@code
 * storm$cleanCounter} (an {@code int} field the patch defines on the class; value is {@code counter
 * + 1} so the default {@code 0} means "unset"). While the pass counter is unchanged and the square
 * was verified clean, the body is skipped with a single field comparison and no JNI. Dirty squares
 * fall through to the vanilla body (costing one redundant dirty check — dirty squares are the small
 * minority, see the class's own {@code notDirty}/{@code dirty} counters).
 *
 * <p>Staleness bound: dirty flags are published by the lighting state the pass counter describes,
 * so a flag that appears mid-counter is picked up when the counter next advances — at worst one
 * lighting-update period later, the same window vanilla already tolerates for processed squares.
 */
public class JniLightingCleanSkipAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.FieldValue("playerIndex") int playerIndex,
            @Advice.FieldValue("updateTick") int updateTick,
            @Advice.FieldValue("square") IsoGridSquare square,
            @Advice.FieldValue(value = "storm$cleanCounter", readOnly = false) int cleanCounter) {
        if (playerIndex == -1 || square == null) {
            return false;
        }
        int counter = LightingJNI.getUpdateCounter(playerIndex);
        if (counter == -1 || updateTick == counter) {
            return false;
        }
        if (cleanCounter == counter + 1) {
            return true;
        }
        if (!LightingJNI.getSquareDirty(playerIndex, square.x, square.y, square.z + 32)) {
            cleanCounter = counter + 1;
            return true;
        }
        cleanCounter = 0;
        return false;
    }
}
