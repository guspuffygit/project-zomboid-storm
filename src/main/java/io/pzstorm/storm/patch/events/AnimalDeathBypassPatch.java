package io.pzstorm.storm.patch.events;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Covers the animal death paths that never reach {@code OnDeath()} — without this, those deaths
 * fire no {@code OnDeath}/{@code OnAnimalDeath} events at all. One instance per target class:
 *
 * <ul>
 *   <li>{@code zombie.iso.objects.IsoHutch} — {@code killAnimal(IsoAnimal)} sets health to 0 and
 *       constructs the {@code IsoDeadBody} directly. Reached by meta-predator kills ({@code
 *       IsoAnimal.checkKilledByMetaPredator} → {@code hutch.killAnimal}) and by in-hutch
 *       health-drain deaths (dirt/starvation/old age via {@code updateAnimalInside}).
 *   <li>{@code zombie.characters.IsoGameCharacter} — {@code doDeathSplatterAndSounds(...)} is
 *       called by the Lua inventory-kill action ({@code ISKillAnimalInInventory}), which kills a
 *       carried animal and corpse-ifies it via {@code IsoDeadBody.new} from Lua, skipping {@code
 *       DoDeath} entirely. The normal path is deduplicated by {@code AnimalDeathEvents}.
 *   <li>{@code zombie.characters.animals.IsoAnimal} — {@code killed(IsoPlayer)} stamps {@code
 *       attackedBy} for the Lua slaughter action ({@code ISKillAnimal}) so the ensuing
 *       state-machine death attributes the killer. No event fires from this seam.
 * </ul>
 *
 * <p>Deliberately NOT woven, because they re-materialize corpses of animals that already died (and
 * already fired events): {@code IsoButcherHook.removeHook}, {@code
 * BaseVehicle.removeAnimalFromTrailer}, corpse-item placement ({@code
 * InventoryItem.createDefaultDeadBody}), world-gen decoration corpses ({@code RDSRatKing}/{@code
 * RDSRatInfested}), and the client-only mirror in {@code NetworkPlayerAI.parse(AnimalPacket)}.
 *
 * <p>Registered server-only next to {@link OnDeathTriggerPatch}; animal simulation is
 * server-authoritative in MP.
 */
public class AnimalDeathBypassPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.ondeath.";

    public AnimalDeathBypassPatch(String className) {
        super(className);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        switch (getClassName()) {
            case "zombie.iso.objects.IsoHutch":
                return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "HutchKillAnimalAdvice").resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("killAnimal")
                                                .and(ElementMatchers.takesArguments(1))));
            case "zombie.characters.IsoGameCharacter":
                return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "AnimalDeathSplatterAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("doDeathSplatterAndSounds")
                                                .and(ElementMatchers.takesArguments(3))));
            case "zombie.characters.animals.IsoAnimal":
                return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "AnimalKilledAttributionAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("killed")
                                                .and(ElementMatchers.takesArguments(1))));
            default:
                throw new IllegalArgumentException(
                        "AnimalDeathBypassPatch has no advice for " + getClassName());
        }
    }
}
