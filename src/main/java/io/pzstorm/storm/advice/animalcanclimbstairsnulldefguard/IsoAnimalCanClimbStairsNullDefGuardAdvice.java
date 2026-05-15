package io.pzstorm.storm.advice.animalcanclimbstairsnulldefguard;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import net.bytebuddy.asm.Advice;
import zombie.characters.animals.IsoAnimal;
import zombie.network.GameServer;

/**
 * Advice for {@code IsoAnimal.canClimbStairs()} that prevents a null {@code adef} from crashing the
 * per-tick {@code postupdate} -&gt; {@code doStairs} path.
 *
 * <p>On the server only: if {@code adef} is null, returns {@code true} from
 * {@code @OnMethodEnter(skipOn = OnNonDefaultValue.class)} to skip the original body, which causes
 * the boolean method to return its default value ({@code false}). Healthy animals return {@code
 * false} from the advice and the original {@code canClimbStairs()} body runs.
 *
 * <p>No lambdas / streams &mdash; advice bodies are inlined into the target method and must be
 * plain imperative Java.
 */
public class IsoAnimalCanClimbStairsNullDefGuardAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This IsoAnimal self) {
        if (!GameServer.server) {
            return false;
        }
        if (self.adef != null) {
            return false;
        }
        LOGGER.warn(
                "IsoAnimalCanClimbStairsNullDefGuardPatch: null adef on canClimbStairs;"
                        + " returning false type={} animalId={}",
                self.getAnimalType(),
                self.getAnimalID());
        return true;
    }
}
