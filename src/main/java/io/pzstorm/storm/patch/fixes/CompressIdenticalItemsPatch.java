package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes the server save crash triggered by a null-animal {@code AnimalInventoryItem}:
 *
 * <pre>NullPointerException: Cannot invoke "IsoAnimal.save(...)" because "this.animal" is null
 *     at AnimalInventoryItem.save(...)</pre>
 *
 * <p>Every container save (world objects, player inventory, nested containers) funnels through
 * {@code CompressIdenticalItems.save(ByteBuffer, ArrayList, IsoGameCharacter)}, which writes a
 * short count prefix then iterates items. If any {@code AnimalInventoryItem} has a null animal the
 * whole server save aborts and nothing is persisted.
 *
 * <p>This patch filters null-animal {@code AnimalInventoryItem}s out of the list on method entry.
 * The count prefix and iteration then operate over the filtered list, so the buffer stays
 * self-consistent and {@code load} sees a coherent stream (one less item than before, no dangling
 * animal bytes). The caller's list is not mutated &mdash; we reassign the parameter slot to a copy
 * only when a drop is needed.
 *
 * <p>Advice is loaded via {@code typePool.describe().resolve()} so Byte Buddy parses it via ASM
 * without triggering class loading of referenced game types.
 */
public class CompressIdenticalItemsPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.compressidenticalitems.";

    public CompressIdenticalItemsPatch() {
        super("zombie.inventory.CompressIdenticalItems");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "FilterSaveAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("save")
                                        .and(ElementMatchers.isStatic())
                                        .and(ElementMatchers.takesArguments(3))
                                        .and(ElementMatchers.takesArgument(1, ArrayList.class))));
    }
}
