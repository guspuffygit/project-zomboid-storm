package io.pzstorm.storm.advice.inventoryweight;

import io.pzstorm.storm.inventory.StormInventoryWeight;
import net.bytebuddy.asm.Advice;

/**
 * Bumps {@link StormInventoryWeight#epoch} on exit (including exceptional exit &mdash; a mutation
 * may have landed before the throw) of inventory-mutating methods, invalidating every character's
 * weight memo. Applied by {@code ItemContainerMutationEpochPatch}, {@code
 * WornItemsMutationEpochPatch} and the hand-setter matcher in {@code
 * IsoGameCharacterInvWeightMemoPatch}; all target methods are cold relative to the per-tick weigh
 * traffic the memo removes.
 */
public class InventoryWeightEpochBumpAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        StormInventoryWeight.bump();
    }
}
