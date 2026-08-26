package io.pzstorm.storm.advice.isocellgetanimals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoMovingObject;

/**
 * Advice for {@code IsoCell.getAnimals()}.
 *
 * <p>Vanilla builds the result as a {@code LinkedList}, and its heaviest caller — {@code
 * IsoAnimal.findMotherAndAttach}, reached from {@code reattachBackToMom} whenever an animal's
 * mother is not loaded — walks that list with an indexed {@code for (i) get(i)} loop. On a {@code
 * LinkedList} each {@code get(i)} is a traversal from the head, so the scan is O(n²) in the animal
 * count: profiled at 1.25% of the server main thread on ATF prod 2026-08-25 with ~1,600 animals
 * (scan #3). Orphaned animals whose mother is gone for good re-run the scan forever on a ~50
 * game-time-unit timer, so the cost does not decay.
 *
 * <p>This advice replaces the method body with the same filter loop accumulating into an {@code
 * ArrayList}, which makes every indexed caller O(n). Contents and iteration order are identical
 * ({@code objectList} is a {@code HashSet}, so vanilla's order was already unspecified); the
 * declared return type {@code List<IsoAnimal>} is unchanged.
 */
public class GetAnimalsArrayListAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static ArrayList<IsoAnimal> onEnter(
            @Advice.FieldValue("objectList") Set<IsoMovingObject> objectList) {
        ArrayList<IsoAnimal> animals = new ArrayList<>();
        for (IsoMovingObject obj : objectList) {
            if (obj instanceof IsoAnimal) {
                animals.add((IsoAnimal) obj);
            }
        }
        return animals;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter ArrayList<IsoAnimal> animals,
            @Advice.Return(readOnly = false) List<IsoAnimal> result) {
        result = animals;
    }
}
