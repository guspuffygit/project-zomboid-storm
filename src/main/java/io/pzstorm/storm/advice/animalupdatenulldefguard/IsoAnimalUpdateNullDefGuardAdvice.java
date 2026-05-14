package io.pzstorm.storm.advice.animalupdatenulldefguard;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import net.bytebuddy.asm.Advice;
import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoCell;
import zombie.network.GameServer;

/**
 * Advice for {@code IsoAnimal.update()} that prevents an animal with {@code adef == null} from
 * crashing the server's per-tick update loop.
 *
 * <p>On the server only: if {@code adef} is null, attempts one re-resolve via {@code
 * AnimalDefinitions.getDef(type)}. If that still yields null, queues the animal onto its cell's
 * {@code removeList} (drained at the end of the tick) and returns {@code true} to skip the original
 * body via {@code skipOn = OnNonDefaultValue.class}. Healthy animals return {@code false} and the
 * original {@code update()} runs unchanged.
 *
 * <p>No lambdas / streams &mdash; advice bodies are inlined into the target method and must be
 * plain imperative Java.
 */
public class IsoAnimalUpdateNullDefGuardAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This IsoAnimal self) {
        if (!GameServer.server) {
            return false;
        }
        if (self.adef != null) {
            return false;
        }
        AnimalDefinitions resolved = AnimalDefinitions.getDef(self.getAnimalType());
        if (resolved != null) {
            self.adef = resolved;
            LOGGER.warn(
                    "IsoAnimalUpdateNullDefGuardPatch: recovered null adef for type={} animalId={}",
                    self.getAnimalType(),
                    self.getAnimalID());
            return false;
        }
        IsoCell cell = self.getCell();
        if (cell != null) {
            cell.getRemoveList().add(self);
        }
        LOGGER.warn(
                "IsoAnimalUpdateNullDefGuardPatch: queued unrecognized-type animal for removal"
                        + " type={} animalId={} cellPresent={}",
                self.getAnimalType(),
                self.getAnimalID(),
                cell != null);
        return true;
    }
}
