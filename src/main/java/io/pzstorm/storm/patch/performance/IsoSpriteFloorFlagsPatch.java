package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.pool.TypePool;

/**
 * Adds the {@code stormFloorFlags} slot (public {@code Object}) to {@code
 * zombie.iso.sprite.IsoSprite} with {@link io.pzstorm.storm.entity.StormSpriteFloorFlagsHolder}
 * accessors; {@code StormFloorFlags} caches the natural/sand/dirt classification of the sprite name
 * there. Pairs with {@link IsoGridSquareFloorFlagsPatch}. Server-only by registration gate.
 */
public class IsoSpriteFloorFlagsPatch extends StormClassTransformer {

    public IsoSpriteFloorFlagsPatch() {
        super("zombie.iso.sprite.IsoSprite");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.defineField("stormFloorFlags", Object.class, Visibility.PUBLIC)
                .implement(
                        typePool.describe("io.pzstorm.storm.entity.StormSpriteFloorFlagsHolder")
                                .resolve())
                .intercept(FieldAccessor.ofBeanProperty());
    }
}
