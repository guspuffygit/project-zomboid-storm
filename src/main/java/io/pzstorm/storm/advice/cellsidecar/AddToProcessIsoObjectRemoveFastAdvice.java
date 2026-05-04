package io.pzstorm.storm.advice.cellsidecar;

import io.pzstorm.storm.iso.StormCellMembership;
import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoCell;
import zombie.iso.IsoObject;

/**
 * Replaces the body of {@code IsoCell.addToProcessIsoObjectRemove(IsoObject)}. The vanilla
 * implementation does two O(n) {@code ArrayList.contains} scans per call (one against {@code
 * processIsoObject}, one against {@code processIsoObjectRemove}). The first of those is the
 * dominant cost during chunk unload bursts because it fires for every removed {@code IsoObject}
 * regardless of whether it was ever a "ticking" object — {@code IsoObject.removeFromWorld()} calls
 * this unconditionally.
 *
 * <p>The replacement consults two {@link java.util.IdentityHashMap}-backed sets via {@link
 * StormCellMembership}, making both checks O(1).
 */
public class AddToProcessIsoObjectRemoveFastAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(
            @Advice.This IsoCell cell,
            @Advice.Argument(0) IsoObject object,
            @Advice.FieldValue("processIsoObject") ArrayList<IsoObject> processList,
            @Advice.FieldValue("processIsoObjectRemove") ArrayList<IsoObject> removeList) {
        StormCellMembership.addToProcessIsoObjectRemove(cell, object, processList, removeList);
        return true;
    }
}
