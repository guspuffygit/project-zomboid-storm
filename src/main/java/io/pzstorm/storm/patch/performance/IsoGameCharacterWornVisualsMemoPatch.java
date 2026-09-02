package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.pool.TypePool;

/**
 * Adds the {@code stormWornVisuals} slot (public {@code Object}) to {@code
 * zombie.characters.IsoGameCharacter} and implements {@link
 * io.pzstorm.storm.entity.StormWornVisualsHolder} over it, giving {@code StormClothingVisuals} a
 * per-character home for its worn-item → visual memo. Server-only by registration gate.
 */
public class IsoGameCharacterWornVisualsMemoPatch extends StormClassTransformer {

    public IsoGameCharacterWornVisualsMemoPatch() {
        super("zombie.characters.IsoGameCharacter");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.defineField("stormWornVisuals", Object.class, Visibility.PUBLIC)
                .implement(
                        typePool.describe("io.pzstorm.storm.entity.StormWornVisualsHolder")
                                .resolve())
                .intercept(FieldAccessor.ofBeanProperty());
    }
}
