package io.pzstorm.storm.advice.isogridsquarelos;

import net.bytebuddy.asm.Advice;
import zombie.iso.IsoGridSquare;

/**
 * Resets the LOS slots Storm added to {@code IsoGridSquare.lighting} when a square is discarded.
 *
 * <p>Vanilla {@code discard()} clears its per-slot lighting state with
 *
 * <pre>
 *   for (int i = 0; i &lt; 4; i++) {
 *       if (this.lighting[i] != null) this.lighting[i].reset();
 *       this.lightInfo[i] = null;
 *   }
 * </pre>
 *
 * <p>The bound is the literal {@code 4} — vanilla's {@code lighting} length. {@code
 * IsoGridSquareLosParallelPatch} grows that array to {@link
 * io.pzstorm.storm.los.StormServerLosConfig#MAX}, so on a Storm server slots {@code 4..MAX-1} are
 * populated by {@code IsoGridSquareCalcVisibilityAdvice} but never reset. A discarded square goes
 * into {@code isoGridSquareCache} and is handed back out for a different world position with those
 * slots still holding the previous location's {@code bCouldSee} bits, which stay live until the
 * next {@code CalcVisibility} happens to run for that specific slot.
 *
 * <p><b>Why this is a separate loop and not a wider vanilla bound.</b> The vanilla loop also does
 * {@code lightInfo[i] = null}, and {@code lightInfo} is a {@code private final ColorInfo[4]} that
 * Storm deliberately leaves at length 4 (the server LOS path never indexes it by slot). Simply
 * widening the vanilla loop to {@code lighting.length} would walk {@code lightInfo} out of bounds
 * on the first added slot. This advice therefore starts at index 4 and touches {@code lighting}
 * only.
 *
 * <p><b>Why {@code reset()} and not {@code null}.</b> Nulling would also release the {@code
 * ServerLighting} instances — the memory this array multiplies — but {@code discard()} is reachable
 * off the main thread ({@code WorldReuserThread}), and {@code CalcVisibility} dereferences {@code
 * this.lighting[playerIndex]} directly after {@code IsoGridSquareCalcVisibilityAdvice} has filled
 * it. A concurrent null would turn that read into an NPE, which vanilla's reset-in-place never can.
 * Reclaiming the slots needs a guarantee that no LOS worker can observe a square mid-discard; until
 * then this advice matches vanilla's semantics exactly, just over the slots vanilla does not know
 * about.
 *
 * <p>Server-only by registration, like the rest of {@code IsoGridSquareLosParallelPatch}.
 */
public class IsoGridSquareDiscardAdvice {

    /** First slot beyond vanilla's own {@code lighting} bound. */
    private static final int VANILLA_LIGHTING_SLOTS = 4;

    @Advice.OnMethodExit
    public static void onExit(@Advice.This IsoGridSquare sq) {
        IsoGridSquare.ILighting[] lighting = sq.lighting;
        for (int i = VANILLA_LIGHTING_SLOTS; i < lighting.length; i++) {
            if (lighting[i] != null) {
                lighting[i].reset();
            }
        }
    }
}
