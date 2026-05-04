package io.pzstorm.storm.advice.cellsidecar;

import io.pzstorm.storm.iso.StormCellMembership;
import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoCell;
import zombie.iso.IsoObject;

/**
 * Replaces the body of {@code IsoCell.addToStaticUpdaterObjectList(IsoObject)}. In addition to
 * making the duplicate-check O(1), it primes the per-object index sidecar that powers the O(1)
 * swap-with-last removal in {@code IsoObject.removeFromWorld()} (see {@code
 * IsoObjectStaticUpdaterRemoveSubstPatch}).
 */
public class AddToStaticUpdaterFastAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(
            @Advice.This IsoCell cell,
            @Advice.Argument(0) IsoObject object,
            @Advice.FieldValue("staticUpdaterObjectList") ArrayList<IsoObject> list) {
        StormCellMembership.addToStaticUpdaterObjectList(cell, object, list);
        return true;
    }
}
