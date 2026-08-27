package io.pzstorm.storm.advice.fastcontains;

import io.pzstorm.storm.util.StormFastContainsList;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Constructor-exit advice for {@code DesignationZoneAnimal}: replaces the freshly initialized
 * {@code foodOnGround} field with {@link StormFastContainsList} so {@code addFoodOnGround}'s
 * membership probe (run for every food item on every zone pass) resolves in O(1). The patch strips
 * {@code final} from the field so the write verifies; the constructor body runs {@code check()}
 * before this advice, so the copy preserves anything it added.
 */
public class DesignationZoneAnimalFoodSwapAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.FieldValue(
                            value = "foodOnGround",
                            readOnly = false,
                            typing = Assigner.Typing.DYNAMIC)
                    Object foodOnGround) {
        foodOnGround = StormFastContainsList.copyOf(foodOnGround);
    }
}
