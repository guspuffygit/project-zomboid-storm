package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.ArrayList;
import java.util.concurrent.atomic.LongAdder;
import zombie.characters.animals.IsoAnimal;

/**
 * Pure logic behind {@link AnimalIgnoredTroughExpiryPatch}: forgets an animal's trough blacklist
 * every time vanilla re-evaluates its zone, so one failed approach no longer starves it for as long
 * as its chunk stays loaded.
 *
 * <h2>The bug this heals</h2>
 *
 * <p>{@code IsoAnimal.ignoredTrough} is the list {@code BaseAnimalBehavior.tryDrinkFromTrough} and
 * {@code tryEatFromTrough} skip when picking a trough. Vanilla adds to it from four places — {@code
 * drinkFromTrough} / {@code eatFromTrough} when the animal arrives farther than {@code distToEat}
 * from the trough, {@code IsoAnimal.pathFailed} when the pathfinder gives up on the way there, and
 * {@code pathToTrough} when the standing square is occupied — and clears it in exactly one: {@code
 * removeFromWorld}. There is no timer and no retry. A single transient failure (another animal on
 * the approach square, a path request that fails while a chunk is loading, a stray target that left
 * the animal a few tiles short) removes that trough from the animal's world until the chunk
 * unloads. On a dedicated server where players keep their base chunks loaded for days, a pen with
 * one trough turns that into livestock dying of thirst beside a full trough.
 *
 * <h2>The fix</h2>
 *
 * <p>Exit advice on the private {@code IsoAnimal.checkZone()} clears {@code ignoredTrough} on the
 * passes where the zone check actually ran (its {@code zoneCheckTimer} had expired), which is the
 * cadence vanilla already uses to rebuild the animal's connected zones and therefore its candidate
 * trough list — 2000 game-time multiplier units, roughly half a real minute. Between two checks the
 * blacklist still does its job of steering the animal to a different trough; after the next check
 * every trough is a candidate again. A truly unreachable trough costs one extra path request per
 * check, and animals only look for a trough when hungry or thirsty in the first place.
 *
 * <p>Any throw latches {@link #broken} and the advice becomes a no-op — vanilla's permanent
 * blacklist returns and the server keeps running.
 */
public final class AnimalIgnoredTroughExpiry {

    /** Permanent fail-soft latch: any throw reverts to vanilla's never-cleared blacklist. */
    private static volatile boolean broken;

    private static final LongAdder EXPIRED_TROUGHS = new LongAdder();

    private AnimalIgnoredTroughExpiry() {}

    /**
     * Exit driver for {@code IsoAnimal.checkZone()}, called only on passes where the zone check
     * ran. Parameter typed {@code Object} so the inlined advice does not embed a checkcast against
     * a game class.
     */
    public static void onZoneCheck(Object animalRef) {
        if (broken) {
            return;
        }
        try {
            expire(((IsoAnimal) animalRef).ignoredTrough);
        } catch (Throwable t) {
            broken = true;
            LOGGER.error(
                    "Storm: animal ignored-trough expiry failed; reverting to vanilla behavior", t);
        }
    }

    /**
     * Clears {@code ignoredTrough} and tallies what it held.
     *
     * @return how many blacklisted troughs were forgotten
     */
    public static int expire(ArrayList<?> ignoredTrough) {
        if (ignoredTrough == null || ignoredTrough.isEmpty()) {
            return 0;
        }
        int count = ignoredTrough.size();
        ignoredTrough.clear();
        EXPIRED_TROUGHS.add(count);
        return count;
    }

    public static boolean isBroken() {
        return broken;
    }

    public static long getExpiredTroughs() {
        return EXPIRED_TROUGHS.sum();
    }

    /** Test hook: clears the fail-soft latch. */
    public static void resetBroken() {
        broken = false;
    }
}
