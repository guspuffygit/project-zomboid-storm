package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes the vanilla bug where a corpse whose serialized form exceeds 20 KB can never be picked up,
 * dragged or stored, on any client, forever. See {@link ItemByteDataWriter} for the mechanism.
 *
 * <p>Applies on both client and server: {@code IsoDeadBody.getItem()} runs on each side (the client
 * to build the {@code ISGrabCorpseAction}, the server to run its {@code complete()}), and a throw
 * on either side alone leaves the corpse unusable. Cheaper tiers cannot reach it — the throw
 * happens inside the Java call Lua makes to obtain the corpse item, before any Lua of ours could
 * run.
 */
public class InventoryItemStoreByteDataPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.inventoryitemstorebytedata.";

    public InventoryItemStoreByteDataPatch() {
        super("zombie.inventory.InventoryItem");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "InventoryItemStoreByteDataAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("storeInByteData")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
