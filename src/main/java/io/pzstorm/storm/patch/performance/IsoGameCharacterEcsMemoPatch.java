package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.description.modifier.FieldManifestation;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.pool.TypePool;

/**
 * Adds the {@code stormEcsMemo} field (public volatile {@code Object[]}) to {@code
 * zombie.characters.IsoGameCharacter} and implements {@link
 * io.pzstorm.storm.entity.StormEcsMemoHolder} with accessors over it — the per-character storage
 * for {@link EcsEntityTryGetMemoPatch}'s component-lookup memo. Characters only: {@code IsoObject}
 * also implements {@code ECSEntity} but exists in the millions, and the hot {@code
 * tryGetECSComponent} chains ({@code getOwner}, {@code getStateMachineComponent}, {@code
 * getFrameKeeper}) are all character-side.
 *
 * <p>Covers every subclass — {@code IsoPlayer}, {@code IsoZombie}, and {@code IsoAnimal} (which
 * extends {@code IsoPlayer}). Server-only by registration gate ({@code StormEnv.isStormServer()}).
 */
public class IsoGameCharacterEcsMemoPatch extends StormClassTransformer {

    public IsoGameCharacterEcsMemoPatch() {
        super("zombie.characters.IsoGameCharacter");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.defineField(
                        "stormEcsMemo",
                        Object[].class,
                        Visibility.PUBLIC,
                        FieldManifestation.VOLATILE)
                .implement(
                        typePool.describe("io.pzstorm.storm.entity.StormEcsMemoHolder").resolve())
                .intercept(FieldAccessor.ofField("stormEcsMemo"));
    }
}
