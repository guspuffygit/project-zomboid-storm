package io.pzstorm.storm.advice.compressidenticalitems;

import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.AnimalInventoryItem;

/**
 * Advice for {@code CompressIdenticalItems.save(ByteBuffer, ArrayList, IsoGameCharacter)}.
 *
 * <p>Drops any {@link AnimalInventoryItem} whose backing animal is null from the items list before
 * the original body runs. The count prefix written by the method is derived from the filtered list,
 * so the serialized buffer stays self-consistent for {@code load}. The caller's list is not mutated
 * &mdash; we only reassign the parameter slot when we actually need to drop something.
 *
 * <p>No lambdas / streams &mdash; advice bodies are inlined into the target method and must be
 * plain imperative Java.
 */
public class FilterSaveAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.Argument(value = 1, readOnly = false) ArrayList<InventoryItem> items) {
        if (items == null) {
            return;
        }
        ArrayList<InventoryItem> filtered = null;
        int size = items.size();
        for (int i = 0; i < size; i++) {
            InventoryItem item = items.get(i);
            boolean drop =
                    item instanceof AnimalInventoryItem
                            && ((AnimalInventoryItem) item).getAnimal() == null;
            if (drop && filtered == null) {
                filtered = new ArrayList<InventoryItem>(size);
                for (int j = 0; j < i; j++) {
                    filtered.add(items.get(j));
                }
            }
            if (!drop && filtered != null) {
                filtered.add(item);
            }
        }
        if (filtered != null) {
            items = filtered;
        }
    }
}
