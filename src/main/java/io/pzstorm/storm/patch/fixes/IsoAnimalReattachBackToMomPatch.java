package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes the server update crash triggered by a null entry in a vehicle's animal list:
 *
 * <pre>NullPointerException: Cannot invoke "IsoAnimal.getAnimalID()" because "mom" is null
 *     at IsoAnimal.reattachBackToMom(...)</pre>
 *
 * <p>{@code reattachBackToMom} iterates {@code this.getVehicle().getAnimals()} without a null
 * check, but the list can legitimately contain nulls (see the {@code if (animal != null)} guard in
 * {@code BaseVehicle.update}). One bad slot aborts the whole tick.
 *
 * <p>This patch applies an {@code @Advice.OnMethodEnter} that sweeps the vehicle's animal list and
 * removes any null entries in place. Removal self-heals the list for the second iteration inside
 * the same method ({@code findMotherAndAttach} over {@code connectedDZone} squares still reads only
 * the cell/zone lists, but the vehicle list is also reused by the outer tick loop).
 *
 * <p>Advice is loaded via {@code typePool.describe().resolve()} so Byte Buddy parses it via ASM
 * without triggering class loading of referenced game types.
 */
public class IsoAnimalReattachBackToMomPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.reattachbacktomom.";

    public IsoAnimalReattachBackToMomPatch() {
        super("zombie.characters.animals.IsoAnimal");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "FilterVehicleAnimalsAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("reattachBackToMom")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
