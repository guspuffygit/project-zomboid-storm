package io.pzstorm.storm.advice.animalupdatenulldefguard;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import net.bytebuddy.asm.Advice;
import zombie.MovingObjectUpdateScheduler;
import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoCell;
import zombie.network.GameServer;

/**
 * Advice for {@code IsoAnimal.update()} that prevents an animal with {@code adef == null} from
 * crashing the server's per-tick update loop.
 *
 * <p>On the server only: if {@code adef} is null, attempts one re-resolve via {@code
 * AnimalDefinitions.getDef(type)}. If that still yields null, detaches the animal from the world
 * immediately &mdash; by hand &mdash; and returns {@code true} to skip the original body via {@code
 * skipOn = OnNonDefaultValue.class}. Healthy animals return {@code false} and the original {@code
 * update()} runs unchanged.
 *
 * <p>The removal must be immediate rather than deferred through {@code IsoCell.getRemoveList()}.
 * Right after the scheduler pass, {@code IsoCell.ProcessObjects} iterates {@code objectList} and
 * calls {@code updateLoopingSounds()} on every animal &mdash; with {@code data == null} that NPEs
 * in {@code getBreed()}, aborting {@code IsoCell.update()} before the remove-list drain at {@code
 * ObjectDeletionAddition()}. A merely-queued animal therefore survived to the next tick and the
 * guard re-fired forever, while the never-drained remove list backed up thousands of dead bodies.
 *
 * <p>The removal must also be by hand: {@code IsoAnimal.removeFromWorld()} itself NPEs for these
 * animals ({@code releaseRagdollController} &rarr; {@code getAnimationPlayer} &rarr; {@code
 * AnimalVisual.getModel} reads {@code AnimalDefinitions.getDef(type).textureSkinned} with a null
 * def &mdash; verified live against a production server). So this advice detaches exactly the
 * collections that make the animal reachable per-tick &mdash; {@code objectList}, {@code addList},
 * {@code removeList}, the scheduler buckets, and the current/last square moving-object lists
 * &mdash; and touches no animation or visual code.
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
            cell.getObjectList().remove(self);
            cell.getAddList().remove(self);
            cell.getRemoveList().remove(self);
            MovingObjectUpdateScheduler.instance.removeObject(self);
            if (self.getCurrentSquare() != null) {
                self.getCurrentSquare().getMovingObjects().remove(self);
            }
            if (self.getLastSquare() != null) {
                self.getLastSquare().getMovingObjects().remove(self);
            }
        }
        LOGGER.warn(
                "IsoAnimalUpdateNullDefGuardPatch: removed unrecognized-type animal from world"
                        + " type={} animalId={} cellPresent={}",
                self.getAnimalType(),
                self.getAnimalID(),
                cell != null);
        return true;
    }
}
