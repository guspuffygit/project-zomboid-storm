package io.pzstorm.storm.advice.inventoryweight;

import io.pzstorm.storm.entity.StormInvWeightHolder;
import io.pzstorm.storm.entity.StormWornItemsOwnerHolder;
import io.pzstorm.storm.inventory.StormInventoryWeight;
import net.bytebuddy.asm.Advice;

/**
 * Memo for {@code IsoGameCharacter.getInventoryWeight()}: skips the recursive inventory walk when
 * the character's packed memo carries {@code stormInvEpoch + 1}, and stores the freshly computed
 * weight otherwise. See {@link StormInventoryWeight} for the epoch sources and {@link
 * StormInvWeightHolder} for the packed layout.
 *
 * <p>The epoch is snapshotted on entry; if it moved while the vanilla walk ran (a mutation landed
 * mid-weigh, only possible off-main) the result is not stored, so a memo can never carry a value
 * computed against a superseded inventory. A miss also stamps this character as the owner of its
 * {@code WornItems} so worn-item mutations can find their way back here.
 *
 * <p>Fail-soft: if the field/interface redefinition did not apply, the {@code instanceof} check
 * fails and every call runs the vanilla body untouched.
 *
 * <p>No lambdas / streams &mdash; advice bodies are inlined into the target method.
 */
public class InventoryWeightMemoAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.This Object self,
            @Advice.Local("stormMemoWeight") float memoWeight,
            @Advice.Local("stormEpochAtEnter") int epochAtEnter) {
        if (!(self instanceof StormInvWeightHolder)) {
            return 0;
        }
        StormInvWeightHolder holder = (StormInvWeightHolder) self;
        epochAtEnter = holder.getStormInvEpoch();
        long packed = holder.getStormInvWeight();
        if ((int) (packed >>> 32) != epochAtEnter + 1) {
            return 0;
        }
        memoWeight = Float.intBitsToFloat((int) packed);
        StormInventoryWeight.hits++;
        return 1;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.This Object self,
            @Advice.FieldValue("wornItems") Object wornItems,
            @Advice.Enter int code,
            @Advice.Local("stormMemoWeight") float memoWeight,
            @Advice.Local("stormEpochAtEnter") int epochAtEnter,
            @Advice.Return(readOnly = false) float ret) {
        if (code != 0) {
            ret = memoWeight;
            return;
        }
        if (!(self instanceof StormInvWeightHolder)) {
            return;
        }
        StormInventoryWeight.misses++;
        if (wornItems instanceof StormWornItemsOwnerHolder
                && ((StormWornItemsOwnerHolder) wornItems).getStormOwner() != self) {
            ((StormWornItemsOwnerHolder) wornItems).setStormOwner(self);
        }
        StormInvWeightHolder holder = (StormInvWeightHolder) self;
        if (holder.getStormInvEpoch() != epochAtEnter) {
            return;
        }
        holder.setStormInvWeight(
                ((long) (epochAtEnter + 1) << 32) | (Float.floatToRawIntBits(ret) & 0xFFFFFFFFL));
    }
}
