package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes the vanilla bug where {@code SpriteConfig.onAddedToOwner()} is called redundantly on every
 * network sync packet (via {@code GameEntity.receiveSyncEntity} line 723).
 *
 * <p>The vanilla {@code onAddedToOwner()} unconditionally calls {@code initObjectInfo()}, which
 * resets and rebuilds the object/face/tile info every time. When the sprite is in a non-default
 * state (open door, smashed window), the rebuild fails and logs:
 *
 * <pre>WARN: Invalid SpriteConfig object! scripted object = ...</pre>
 *
 * <p>This patch makes {@code onAddedToOwner()} idempotent by skipping re-initialization when the
 * component is already fully initialized (all three info fields are non-null).
 */
public class SpriteConfigFixPatch extends StormClassTransformer {

    public SpriteConfigFixPatch() {
        super("zombie.entity.components.spriteconfig.SpriteConfig");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(OnAddedToOwnerAdvice.class)
                        .on(
                                ElementMatchers.named("onAddedToOwner")
                                        .and(ElementMatchers.takesArguments(0))));
    }

    /**
     * Advice inlined into {@code SpriteConfig.onAddedToOwner()}.
     *
     * <p>If {@code objectInfo}, {@code faceInfo}, and {@code tileInfo} are all non-null, the
     * component is already initialized — skip the method body entirely. This prevents the redundant
     * {@code initObjectInfo()} → {@code resetObjectInfo()} → rebuild cycle that causes the warning
     * spam and GC pressure.
     */
    public static class OnAddedToOwnerAdvice {

        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter(
                @Advice.FieldValue("objectInfo") Object objectInfo,
                @Advice.FieldValue("faceInfo") Object faceInfo,
                @Advice.FieldValue("tileInfo") Object tileInfo) {

            return objectInfo != null && faceInfo != null && tileInfo != null;
        }
    }
}
