package io.pzstorm.storm.advice.movingobjectispushedbyforseparatenulldefguard;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import net.bytebuddy.asm.Advice;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoMovingObject;
import zombie.network.GameServer;

/**
 * Advice for {@code IsoMovingObject.isPushedByForSeparate(IsoMovingObject)} that prevents a null
 * {@code IsoAnimal.adef} on either {@code this} or {@code other} from crashing the physics
 * separation pass.
 *
 * <p>On the server only: if either side is an {@code IsoAnimal} with {@code adef == null}, returns
 * {@code true} from {@code @OnMethodEnter(skipOn = OnNonDefaultValue.class)} to skip the original
 * body, which causes the boolean method to return its default value ({@code false}) &mdash;
 * matching vanilla's "non-collidable or block-movement" branch. Healthy pairs return {@code false}
 * from the advice and the original body runs.
 *
 * <p>No lambdas / streams &mdash; advice bodies are inlined into the target method and must be
 * plain imperative Java.
 */
public class IsoMovingObjectIsPushedByForSeparateNullDefGuardAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.This IsoMovingObject self, @Advice.Argument(0) IsoMovingObject other) {
        if (!GameServer.server) {
            return false;
        }
        if (self instanceof IsoAnimal && ((IsoAnimal) self).adef == null) {
            LOGGER.warn(
                    "IsoMovingObjectIsPushedByForSeparateNullDefGuardPatch: null adef on self;"
                            + " returning false type={} animalId={}",
                    ((IsoAnimal) self).getAnimalType(),
                    ((IsoAnimal) self).getAnimalID());
            return true;
        }
        if (other instanceof IsoAnimal && ((IsoAnimal) other).adef == null) {
            LOGGER.warn(
                    "IsoMovingObjectIsPushedByForSeparateNullDefGuardPatch: null adef on other;"
                            + " returning false type={} animalId={}",
                    ((IsoAnimal) other).getAnimalType(),
                    ((IsoAnimal) other).getAnimalID());
            return true;
        }
        return false;
    }
}
