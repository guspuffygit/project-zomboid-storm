package io.pzstorm.storm.advice.inventoryitemstorebytedata;

import io.pzstorm.storm.patch.fixes.ItemByteDataWriter;
import java.nio.ByteBuffer;
import net.bytebuddy.asm.Advice;

/**
 * Advice inlined into {@code zombie.inventory.InventoryItem.storeInByteData(IsoObject)}.
 *
 * <p>Serializes through {@link ItemByteDataWriter}, which grows its scratch buffer instead of
 * overflowing vanilla's fixed 20 KB one. Returns {@code null} on any failure, which falls through
 * to the vanilla body so the patch can only ever restore vanilla behaviour, never invent new.
 */
public class InventoryItemStoreByteDataAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.Argument(0) Object object,
            @Advice.FieldValue(value = "byteData", readOnly = false) ByteBuffer byteData) {

        ByteBuffer written = ItemByteDataWriter.write(object, byteData);
        if (written == null) {
            return false;
        }
        byteData = written;
        return true;
    }
}
