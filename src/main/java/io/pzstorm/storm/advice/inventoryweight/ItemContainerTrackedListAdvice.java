package io.pzstorm.storm.advice.inventoryweight;

import io.pzstorm.storm.inventory.StormInventoryWeight;
import io.pzstorm.storm.inventory.StormTrackedItemList;
import java.util.ArrayList;
import net.bytebuddy.asm.Advice;

/**
 * Exit advice for every {@code ItemContainer} constructor plus {@code setItems} and {@code emptyIt}
 * &mdash; the only places the {@code items} field is assigned. Replaces the plain {@code ArrayList}
 * with a {@link StormTrackedItemList} bound to this container (keeping any contents) and bumps the
 * owning character's epoch, since {@code setItems} / {@code emptyIt} replace the contents
 * wholesale. Already-tracked lists are left alone (chained constructors run this twice). Applied by
 * {@code ItemContainerTrackedListPatch}.
 */
public class ItemContainerTrackedListAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.This Object self,
            @Advice.FieldValue(value = "items", readOnly = false) ArrayList<Object> items) {
        if (items != null && !(items instanceof StormTrackedItemList)) {
            items = new StormTrackedItemList<Object>(self, items);
        }
        StormInventoryWeight.bumpContainer(self);
    }
}
