package io.pzstorm.storm.advice.inventoryweight;

import io.pzstorm.storm.entity.StormWornItemsOwnerHolder;
import io.pzstorm.storm.inventory.StormInventoryWeight;
import net.bytebuddy.asm.Advice;

/**
 * Exit advice (including exceptional exit &mdash; a mutation may have landed before the throw) for
 * the {@code IsoGameCharacter} methods that change its own weigh inputs: {@code setPrimaryHandItem}
 * / {@code setSecondaryHandItem} (equipped multiplier), {@code setInventory} (whole container swap)
 * and {@code onWornItemsChanged} (fires after {@code initWornItems} / {@code setWornItems} install
 * a new {@code WornItems}). Bumps the character's epoch and stamps the character as owner of its
 * current {@code WornItems} so {@code WornItemsMutationEpochPatch} can route worn-item mutations
 * back to it. Applied by {@code IsoGameCharacterInvWeightMemoPatch}.
 */
public class CharacterInvEpochBumpAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.This Object self, @Advice.FieldValue("wornItems") Object wornItems) {
        StormInventoryWeight.bumpCharacter(self);
        if (wornItems instanceof StormWornItemsOwnerHolder) {
            ((StormWornItemsOwnerHolder) wornItems).setStormOwner(self);
        }
    }
}
