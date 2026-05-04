package io.pzstorm.storm.advice.cellsidecar;

import io.pzstorm.storm.iso.StormCellMembership;
import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoCell;
import zombie.iso.IsoObject;

/**
 * Replaces the body of {@code IsoCell.addToProcessIsoObject(IsoObject)}. The vanilla implementation
 * does an O(n) {@code ArrayList.contains} scan on every call; this delegates to {@link
 * StormCellMembership} which maintains a parallel {@link java.util.IdentityHashMap}-backed set so
 * the membership check is O(1).
 *
 * <p>If the advice throws (e.g. classloader anomaly), {@code suppress} swallows it and the advice
 * returns {@code false}, falling through to the original vanilla body.
 */
public class AddToProcessIsoObjectFastAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(
            @Advice.This IsoCell cell,
            @Advice.Argument(0) IsoObject object,
            @Advice.FieldValue("processIsoObject") ArrayList<IsoObject> processList,
            @Advice.FieldValue("processIsoObjectRemove") ArrayList<IsoObject> removeList) {
        StormCellMembership.addToProcessIsoObject(cell, object, processList, removeList);
        return true;
    }
}
