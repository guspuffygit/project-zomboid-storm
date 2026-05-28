package io.pzstorm.storm.advice.isogridsquarelos;

import net.bytebuddy.asm.Advice;
import zombie.iso.IsoGridSquare;
import zombie.network.ServerLOS;

/**
 * Lazily allocates the per-slot {@code IsoGridSquare.lighting[playerIndex]} on the dedicated
 * server.
 *
 * <p>The vanilla constructor allocates a {@code ServerLOS.ServerLighting} only for slot {@code 0}
 * on the server (slots 1-3 stay {@code null}). When LOS runs with &ge; 2 workers, slots 1-3 are
 * scanned too, so {@code CalcVisibility} would NPE on {@code this.lighting[playerIndex]}. This
 * fills the slot on first use. Concurrent workers never share a slot, so the per-element writes
 * never race.
 *
 * <p>Server-only by registration; {@code ServerLighting} is the correct lighting impl on the
 * server.
 */
public class IsoGridSquareCalcVisibilityAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This IsoGridSquare sq, @Advice.Argument(0) int playerIndex) {
        if (sq.lighting[playerIndex] == null) {
            sq.lighting[playerIndex] = new ServerLOS.ServerLighting();
        }
    }
}
