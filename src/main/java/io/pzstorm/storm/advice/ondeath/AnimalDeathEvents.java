package io.pzstorm.storm.advice.ondeath;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import zombie.Lua.LuaEventManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.animals.IsoAnimal;

/**
 * Shared trigger for the animal death events ({@code OnDeath} + {@code OnAnimalDeath}),
 * deduplicated per animal instance. Needed because vanilla has several animal death paths that
 * never reach {@code IsoGameCharacter.DoDeath()}/{@code OnDeath()}:
 *
 * <ul>
 *   <li>{@code IsoHutch.killAnimal()} — meta-predator kills and in-hutch health-drain deaths set
 *       health to 0 and construct the {@code IsoDeadBody} directly.
 *   <li>{@code ISKillAnimalInInventory} (Lua) — killing a carried animal calls {@code
 *       doDeathSplatterAndSounds()} and {@code IsoDeadBody.new} directly.
 * </ul>
 *
 * <p>{@link OnDeathAdvice} routes the normal animal path through {@link #triggerOnce(Object)} too,
 * so whichever seam sees the death first fires the events and the rest become no-ops. The set is
 * weak-keyed: entries vanish with the animal, and an animal re-materialized from a corpse item
 * (butcher hook, trailer unload) is already excluded because those paths are not woven.
 *
 * <p>All methods take {@code Object} and cast internally so the inlined advice call sites do not
 * encode game-class checkcasts (see the {@code feedback_elided_cast_load} memory).
 */
public final class AnimalDeathEvents {

    private static final Set<IsoAnimal> FIRED =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private AnimalDeathEvents() {}

    /** Fires {@code OnDeath} + {@code OnAnimalDeath} for the animal unless already fired. */
    public static void triggerOnce(Object animalObj) {
        if (!(animalObj instanceof IsoAnimal animal) || !FIRED.add(animal)) {
            return;
        }
        try {
            LuaEventManager.triggerEvent("OnDeath", animal);
            LuaEventManager.triggerEvent("OnAnimalDeath", animal);
        } catch (Throwable t) {
            LOGGER.error("OnAnimalDeath dispatch failed for animal id={}", animal.getOnlineID(), t);
        }
    }

    /**
     * {@code doDeathSplatterAndSounds} seam: fires only for animals that are already dead (the Lua
     * inventory-kill sets health to 0 first; a live animal means a stray mod call). On the normal
     * path {@code DoDeath} fired {@code OnDeath()} a few lines earlier, so dedup makes this a
     * no-op. Attributes the kill to the wielder so the log (and the corpse's {@code killedBy})
     * carry the killer.
     */
    public static void triggerOnceWithKiller(Object animalObj, Object wielderObj) {
        if (!(animalObj instanceof IsoAnimal animal) || animal.getHealth() > 0.0F) {
            return;
        }
        attributeKill(animal, wielderObj);
        triggerOnce(animal);
    }

    /**
     * {@code IsoAnimal.killed(IsoPlayer)} seam: the Lua slaughter action ({@code ISKillAnimal})
     * calls {@code setHealth(0)} + {@code killed(chr)} without ever setting {@code attackedBy}, so
     * the state-machine death that follows loses the killer. Weapon kills already set {@code
     * attackedBy} in {@code hitConsequences}, hence the null guard.
     */
    public static void attributeKill(Object animalObj, Object chrObj) {
        if (animalObj instanceof IsoAnimal animal
                && chrObj instanceof IsoGameCharacter chr
                && animal.getHealth() <= 0.0F
                && animal.getAttackedBy() == null) {
            animal.setAttackedBy(chr);
        }
    }
}
