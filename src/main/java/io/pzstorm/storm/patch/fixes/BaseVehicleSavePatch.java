package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import java.nio.ByteBuffer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes the vehicle-DB save crash triggered by a null entry in a vehicle's animal list:
 *
 * <pre>NullPointerException: Cannot invoke "IsoAnimal.save(...)" because return value of
 *     "ArrayList.get(int)" is null at BaseVehicle.save(...)</pre>
 *
 * <p>{@code BaseVehicle.save} writes {@code animals.size()} as the count prefix then iterates
 * {@code this.animals} and calls {@code save} on each entry without a null check. The game's own
 * {@code BaseVehicle.update} already guards with {@code if (animal != null)}, so the list can
 * legitimately contain nulls &mdash; {@code save} just forgot the same guard.
 *
 * <p>This patch applies an {@code @Advice.OnMethodEnter} that sweeps null entries out of {@code
 * this.animals} in place before the body runs. Because the sweep completes before the count prefix
 * is written, the count and the iterated entries stay self-consistent for {@code load} to read
 * back.
 *
 * <p>Advice is loaded via {@code typePool.describe().resolve()} so Byte Buddy parses it via ASM
 * without triggering class loading of referenced game types.
 */
public class BaseVehicleSavePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.basevehiclesave.";

    public BaseVehicleSavePatch() {
        super("zombie.vehicles.BaseVehicle");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "FilterVehicleAnimalsAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("save")
                                        .and(ElementMatchers.takesArguments(2))
                                        .and(ElementMatchers.takesArgument(0, ByteBuffer.class))
                                        .and(ElementMatchers.takesArgument(1, boolean.class))));
    }
}
