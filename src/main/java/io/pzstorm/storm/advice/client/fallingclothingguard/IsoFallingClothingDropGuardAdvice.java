package io.pzstorm.storm.advice.client.fallingclothingguard;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code IsoFallingClothing.drop()} that handles a null {@code current} square before
 * the vanilla body dereferences it.
 *
 * <p>The null test is inlined so the common case costs one field read and a branch; the recovery
 * lives in {@link FallingClothingDropGuard}, whose class is only loaded the first time an item
 * actually lands with no square. The advice skips the vanilla body when the guard could not recover
 * a square (the guard has already discarded the item), and runs it when the guard did. {@code
 * current} is typed {@code Object} and {@code @Advice.This} is not narrowed to the transform
 * target, so the inlined bytecode never references a type that is still being defined. {@code
 * suppress} resolves any advice failure to "run vanilla".
 */
public class IsoFallingClothingDropGuardAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(
            @Advice.This Object self, @Advice.FieldValue("current") Object current) {
        if (current != null) {
            return false;
        }
        return FallingClothingDropGuard.onNullSquare(self);
    }
}
