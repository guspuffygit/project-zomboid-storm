package io.pzstorm.storm.advice.client.worlditemspriteguard;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code IsoWorldInventoryObject.render} that restores a null {@code sprite} before the
 * vanilla body dereferences it.
 *
 * <p>The null test is inlined so the common case costs one field read and a branch on a per-frame,
 * per-ground-item path; the repair itself lives in {@link WorldItemSpriteGuard}, whose class is
 * only loaded the first time a broken item is actually rendered. {@code sprite} is typed {@code
 * Object} and {@code @Advice.This} is not narrowed to the transform target, so the inlined bytecode
 * never references a type that is still being defined.
 */
public class IsoWorldInventoryObjectRenderSpriteGuardAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.This Object self, @Advice.FieldValue("sprite") Object sprite) {
        if (sprite == null) {
            WorldItemSpriteGuard.restoreSprite(self);
        }
    }
}
