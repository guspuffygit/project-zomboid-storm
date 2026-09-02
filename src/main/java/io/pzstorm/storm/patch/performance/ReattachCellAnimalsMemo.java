package io.pzstorm.storm.patch.performance;

import java.util.List;
import java.util.function.Function;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;

/**
 * Once-per-tick memo of {@code IsoCell.getAnimals()} for {@code IsoAnimal.reattachBackToMom()}.
 *
 * <p>{@code getAnimals()} filters the cell's whole moving-object set — every zombie, player, animal
 * and vehicle in the loaded area — into a fresh list. {@code reattachBackToMom} calls it whenever
 * an animal's mother is not loaded, on a 50 game-time-unit timer that at server tick rates fires
 * every ~12 ticks; orphans whose mother is gone for good retry forever. With 93 such orphans and
 * ~7,700 moving objects on ATF prod that was ~8 full walks per tick (scan #8, 2026-09-02, 13.5% of
 * {@code IsoAnimal.update}). All of them see the same cell within one tick, so the first caller in
 * a tick builds the list and the rest share it.
 *
 * <p>Keyed on {@code IsoWorld.getFrameNo()}, which increments once per {@code IsoWorld.update} —
 * the same tick that runs every animal update — plus the cell's identity. Only ever touched from
 * the main thread (the animal update pass), so the memo is plain statics. The shared list is read
 * only by {@code findMotherAndAttach}; nothing mutates it. Falls back to a direct call when there
 * is no world yet.
 */
public final class ReattachCellAnimalsMemo {

    private static Object memoCell;
    private static int memoFrame = Integer.MIN_VALUE;
    private static List<?> memoAnimals;

    private ReattachCellAnimalsMemo() {}

    /**
     * Substitution target for the {@code this.getCell().getAnimals()} call inside {@code
     * reattachBackToMom}; the receiver arrives as the first argument. Typed {@code Object} so the
     * reflective lookup at patch registration does not load {@code IsoCell}.
     */
    public static List<?> animalsThisTick(Object cell) {
        IsoWorld world = IsoWorld.instance;
        if (world == null) {
            return load(cell);
        }
        return get(world.getFrameNo(), cell, ReattachCellAnimalsMemo::load);
    }

    static List<?> get(int frame, Object cell, Function<Object, List<?>> loader) {
        if (frame == memoFrame && cell == memoCell && memoAnimals != null) {
            return memoAnimals;
        }
        List<?> animals = loader.apply(cell);
        memoFrame = frame;
        memoCell = cell;
        memoAnimals = animals;
        return animals;
    }

    private static List<?> load(Object cell) {
        return ((IsoCell) cell).getAnimals();
    }
}
