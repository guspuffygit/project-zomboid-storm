package io.pzstorm.storm.advice.cellsidecar;

import io.pzstorm.storm.iso.StormCellMembership;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoCell;

/**
 * Wraps {@code IsoCell.ProcessIsoObject()} so the sidecar membership sets are flushed in lockstep
 * with the vanilla {@code processIsoObject.removeAll(processIsoObjectRemove);
 * processIsoObjectRemove.clear()} pair at the top of the method.
 *
 * <p>If we did not mirror that flush, the sidecar's {@code processIsoObjectRemoveSet} would
 * accumulate stale tombstones and {@code addToProcessIsoObjectRemoveFastAdvice} would incorrectly
 * skip valid-but-already-flushed entries.
 *
 * <p>Runs as a non-skipping advice — the original method body still iterates and calls {@code
 * update()} on each tracked object.
 */
public class ProcessIsoObjectFlushAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This IsoCell cell) {
        StormCellMembership.flushProcessIsoObjectRemoves(cell);
    }
}
