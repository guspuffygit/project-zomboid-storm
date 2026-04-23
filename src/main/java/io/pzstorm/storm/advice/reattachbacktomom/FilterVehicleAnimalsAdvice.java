package io.pzstorm.storm.advice.reattachbacktomom;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import net.bytebuddy.asm.Advice;
import zombie.characters.animals.IsoAnimal;
import zombie.vehicles.BaseVehicle;

/**
 * Advice for {@code IsoAnimal.reattachBackToMom()}.
 *
 * <p>Removes any null entries from {@code this.getVehicle().getAnimals()} before the original body
 * runs. The method iterates that list unguarded and dereferences each element to call {@code
 * getAnimalID()}, so a single null slot crashes the whole server update tick. {@code
 * BaseVehicle.update} already skips null entries with an {@code if (animal != null)} guard, which
 * confirms the list can legitimately contain nulls &mdash; this advice mirrors that expectation at
 * the second iteration site.
 *
 * <p>No lambdas / streams &mdash; advice bodies are inlined into the target method and must be
 * plain imperative Java.
 */
public class FilterVehicleAnimalsAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This IsoAnimal self) {
        BaseVehicle vehicle = self.getVehicle();
        if (vehicle == null) {
            return;
        }
        java.util.ArrayList<IsoAnimal> animals = vehicle.getAnimals();
        if (animals == null || animals.isEmpty()) {
            return;
        }
        for (int i = animals.size() - 1; i >= 0; i--) {
            if (animals.get(i) == null) {
                animals.remove(i);
                LOGGER.warn(
                        "IsoAnimalReattachBackToMomPatch: removed null animal from"
                                + " vehicle.animals (index={}, vehicleId={})",
                        i,
                        vehicle.getId());
            }
        }
    }
}
