package io.pzstorm.storm.advice.inventoryweight;

import io.pzstorm.storm.entity.StormInvWeightHolder;
import io.pzstorm.storm.inventory.StormInventoryWeight;
import net.bytebuddy.asm.Advice;

/**
 * Memo for {@code IsoGameCharacter.getInventoryWeight()}: skips the recursive inventory walk when
 * the character's packed memo carries the current {@link StormInventoryWeight#epoch}, and stores
 * the freshly computed weight otherwise. See {@link StormInventoryWeight} for the epoch contract
 * and {@link StormInvWeightHolder} for the packed layout.
 *
 * <p>Fail-soft: if the field/interface redefinition did not apply, the {@code instanceof} check
 * fails and every call runs the vanilla body untouched.
 *
 * <p>No lambdas / streams &mdash; advice bodies are inlined into the target method.
 */
public class InventoryWeightMemoAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.This Object self, @Advice.Local("stormMemoWeight") float memoWeight) {
        if (!(self instanceof StormInvWeightHolder)) {
            return 0;
        }
        long packed = ((StormInvWeightHolder) self).getStormInvWeight();
        if ((int) (packed >>> 32) != StormInventoryWeight.epoch) {
            return 0;
        }
        memoWeight = Float.intBitsToFloat((int) packed);
        StormInventoryWeight.hits++;
        return 1;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.This Object self,
            @Advice.Enter int code,
            @Advice.Local("stormMemoWeight") float memoWeight,
            @Advice.Return(readOnly = false) float ret) {
        if (code != 0) {
            ret = memoWeight;
            return;
        }
        if (self instanceof StormInvWeightHolder) {
            StormInventoryWeight.misses++;
            ((StormInvWeightHolder) self)
                    .setStormInvWeight(
                            ((long) StormInventoryWeight.epoch << 32)
                                    | (Float.floatToRawIntBits(ret) & 0xFFFFFFFFL));
        }
    }
}
