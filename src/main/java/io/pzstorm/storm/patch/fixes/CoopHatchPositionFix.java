package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import zombie.characters.animals.IsoAnimal;
import zombie.iso.objects.IsoHutch;

/**
 * Pure logic behind {@link CoopHatchPositionFixPatch}: give any origin-positioned animal entering a
 * hutch the hutch's own world coordinates.
 *
 * <h2>The bug this heals</h2>
 *
 * <p>{@code Food.checkEggHatch(IsoHutch)} declares {@code int x = 0, y = 0, z = 0} and only assigns
 * them inside its {@code hutch == null} branch (world item / container / vehicle / player). The one
 * caller that passes a non-null hutch — {@code IsoHutch.update()} hatching nest box eggs —
 * therefore constructs every chick as {@code new IsoAnimal(cell, 0, 0, 0, ...)}, i.e. at world
 * position {@code (0.5, 0.5, 0.0)}, the map origin. The {@code x == 0 && y == 0} sanity guard in
 * the same method cannot fire because it too lives inside the {@code hutch == null} branch.
 *
 * <p>The player-visible symptom is sync relevancy, not a chick standing at the corner. {@code
 * AnimalSynchronizationManager.sendUpdateToClient} gates every animal update on {@code
 * connection.RelevantTo(animal.getX(), animal.getY(), ...)}, so an origin-positioned chick never
 * syncs to its owner — the coop UI shows nothing and players report their chicks "gone" — while it
 * does sync, all at once, to any client that happens to stand near the map corner. The bad
 * coordinate outlives the coop: {@code IsoHutch.releaseAnimal} repairs it server-side (enter-spot
 * square before {@code addToWorld()}), but the grab-to-item path ({@code IsoHutch.removeAnimal} via
 * {@code AnimalCommandPacket.HutchGrabAnimal}) keeps the registry entry at origin while the chick
 * is boxed as a {@code Base.Animal} item, and {@code IsoAnimal.save} persists raw x/y/z across
 * restarts. Boxing round-trips safely — {@code DropAnimal} re-creates the animal at the drop
 * coordinates — but a registry entry whose item is lost strands and dies at origin. Measured on a
 * live 42.20.4 server: 128 chicks at exactly {@code (0.5, 0.5, 0.0)} — 116 alive (every one boxed
 * as an item in world containers) and 12 dead (8 died while boxed, 4 stranded unheld).
 *
 * <h2>The fix</h2>
 *
 * <p>Entry advice on {@code IsoHutch.addAnimalInside(IsoAnimal, boolean)} — the funnel every animal
 * passes through on its way into a hutch, covering both the hatch path and {@code IsoHutch.load}
 * re-adding saved animals (which also repairs chicks already saved at origin while still inside a
 * coop). An animal arriving with origin coordinates gets the hutch's enter-spot tile, the same tile
 * {@code releaseAnimal} uses as its fallback.
 *
 * <p>The decision logic ({@link #needsFix}) is split out from the I/O ({@link #ensurePosition}) so
 * it can be unit-tested without game classes on the classpath.
 */
public final class CoopHatchPositionFix {

    private CoopHatchPositionFix() {}

    /**
     * Pure decision: does an animal at {@code (animalX, animalY)} entering a hutch anchored at
     * {@code (hutchSavedX, hutchSavedY)} need its position repaired?
     *
     * <p>An animal constructed at tile {@code (0, 0, 0)} sits at the square centre {@code (0.5,
     * 0.5)}, so the test is a {@code < 1} band, not an exact-zero compare. No legitimate hutch
     * exists near the map origin, and a hutch that itself reports {@code (0, 0)} has no better
     * position to offer.
     *
     * @param animalX the animal's current world x
     * @param animalY the animal's current world y
     * @param hutchSavedX the hutch's saved tile x
     * @param hutchSavedY the hutch's saved tile y
     * @return {@code true} if the animal should be moved to the hutch
     */
    public static boolean needsFix(float animalX, float animalY, int hutchSavedX, int hutchSavedY) {
        if (animalX >= 1.0f || animalY >= 1.0f) {
            return false;
        }
        return hutchSavedX != 0 || hutchSavedY != 0;
    }

    /**
     * Driver called from the {@code IsoHutch.addAnimalInside(IsoAnimal, boolean)} entry advice.
     *
     * <p>Parameters are typed {@code Object} so the inlined advice does not embed checkcasts
     * against game classes into the patched method's bytecode; the casts happen here, on the first
     * actual call, when both classes are guaranteed loaded. See the {@code
     * feedback_elided_cast_load} memory.
     *
     * @param hutchRef the {@code IsoHutch} being entered
     * @param animalRef the {@code IsoAnimal} entering it
     */
    public static void ensurePosition(Object hutchRef, Object animalRef) {
        IsoHutch hutch = (IsoHutch) hutchRef;
        IsoAnimal animal = (IsoAnimal) animalRef;
        if (!needsFix(animal.getX(), animal.getY(), hutch.savedX, hutch.savedY)) {
            return;
        }
        int enterX = 0;
        int enterY = 0;
        try {
            enterX = hutch.getEnterSpotX();
            enterY = hutch.getEnterSpotY();
        } catch (RuntimeException e) {
            // def table not populated yet — the hutch tile itself is still a valid position
        }
        animal.setX(hutch.savedX + enterX);
        animal.setY(hutch.savedY + enterY);
        animal.setZ(hutch.savedZ);
        LOGGER.warn(
                "Repaired origin-positioned {} (id {}) entering hutch at {},{},{}",
                animal.getAnimalType(),
                animal.getOnlineID(),
                hutch.savedX,
                hutch.savedY,
                hutch.savedZ);
    }
}
