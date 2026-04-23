package io.pzstorm.storm.advice.basevehiclesave;

import net.bytebuddy.asm.Advice;
import zombie.characters.animals.IsoAnimal;
import zombie.vehicles.BaseVehicle;

/**
 * Advice for {@code BaseVehicle.save(ByteBuffer, boolean)}.
 *
 * <p>Removes any null entries from {@code this.animals} before the original body runs. {@code
 * BaseVehicle.save} writes {@code animals.size()} as the count prefix then iterates and calls
 * {@code animals.get(i).save(...)} unguarded &mdash; a single null slot aborts the whole
 * vehicle-DB save and can crash shutdown.
 *
 * <p>Because the sweep runs before the count prefix is written, the count and the iterated entries
 * stay self-consistent for {@code load} to read back.
 *
 * <p>No lambdas / streams &mdash; advice bodies are inlined into the target method and must be
 * plain imperative Java.
 */
public class FilterVehicleAnimalsAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This BaseVehicle self) {
        java.util.ArrayList<IsoAnimal> animals = self.getAnimals();
        if (animals == null || animals.isEmpty()) {
            return;
        }
        for (int i = animals.size() - 1; i >= 0; i--) {
            if (animals.get(i) == null) {
                animals.remove(i);
            }
        }
    }
}
