package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import zombie.core.random.Rand;
import zombie.iso.objects.IsoHutch;

/**
 * Pure logic behind {@link HutchDirtRateFixPatch}: makes hutch (chicken coop / rabbit hutch) dirt
 * accrue in game time instead of once per server tick, at the rate the game's own metagame path
 * intends, scaled by the {@code Storm.HutchDirtRatePercent} sandbox option.
 *
 * <h2>The bug this heals</h2>
 *
 * <p>{@code IsoHutch.update()} rolls {@code Rand.NextBool(8000 - 100 * animalsInside)} (floor dirt)
 * once per world tick, and {@code updateAnimalInside} rolls {@code Rand.NextBool(300)} (nest box
 * dirt) once per tick per nesting hen — neither roll is scaled by {@code GameTime.getMultiplier()},
 * unlike every other animal mechanic in the same methods (hunger, thirst, health, stress, egg
 * timers are all multiplier- or hour-based). The accrual rate is therefore proportional to the tick
 * rate: a 10 TPS dedicated server accrues floor dirt at ~5 units per <i>real</i> hour with a 6-bird
 * coop (~118/day — several full cleanings per real day), and in MP it accrues around the clock
 * whenever the chunk is streamed by anyone, owner online or not. The intended rate is visible in
 * {@code IsoHutch.doMeta(hours)}, which runs while the coop is unloaded: one 1-in-{@code min(25 -
 * animals, 10)} roll per <i>game</i> hour for each dirt value — roughly 2.4 dirt per game day, i.e.
 * clean-weekly, not clean-hourly. At 20 dirt healing stops and above it dirt damages the animals,
 * so the runaway accrual is what kills players' chickens.
 *
 * <h2>The fix</h2>
 *
 * <p>Enter/exit advice around {@code IsoHutch.update()}. On entry the current dirt values and
 * {@code lastHourCheck} are captured; on exit any per-tick accrual the vanilla body rolled is
 * reverted and Storm applies its own accrual using {@code doMeta}'s per-game-hour probability —
 * exactly one opportunity per game hour (the same {@code lastHourCheck} cadence the vanilla body
 * already computes for hunger/aging), only while the hutch has animals, scaled by {@code
 * Storm.HutchDirtRatePercent}. The result is tick-rate and framerate independent and consistent
 * with the unloaded metagame rate. Cleaning, healing, dirt damage, and sync are untouched vanilla.
 *
 * <p>The capture/apply pair relies on {@code IsoHutch.update()} being main-thread and
 * non-reentrant, which it is on the dedicated server; the {@code armed} flag makes a surprise
 * nested call degrade to a skipped accrual for that tick rather than a wrong one. Any throw latches
 * {@link #broken} and the advice becomes a no-op — vanilla per-tick accrual returns, the server
 * keeps running.
 */
public final class HutchDirtRateFix {

    /**
     * Percentage of the intended (metagame) dirt rate applied while the coop is loaded. 100 =
     * {@code doMeta}'s rate; 0 = dirt never accrues while loaded.
     */
    public static final int DEFAULT_RATE_PERCENT = 100;

    public static final int MIN_RATE_PERCENT = 0;
    public static final int MAX_RATE_PERCENT = 1000;

    /** Live rate; volatile because the sandbox applier may push updates from another thread. */
    private static volatile int ratePercent = DEFAULT_RATE_PERCENT;

    /** Permanent fail-soft latch: any throw reverts to vanilla per-tick accrual. */
    private static volatile boolean broken;

    // Scratch captured by beforeUpdate and consumed by afterUpdate. IsoHutch.update() runs only on
    // the server main thread and never nests, so plain statics are safe; `armed` turns an
    // unexpected nesting into a skipped tick instead of a corrupt revert.
    private static float preHutchDirt;
    private static float preNestBoxDirt;
    private static int preLastHourCheck;
    private static boolean armed;

    private HutchDirtRateFix() {}

    /**
     * Applies the {@code Storm.HutchDirtRatePercent} sandbox option and pushes the applied value to
     * the Prometheus gauge. Single mutation point — sandbox apply and tests both funnel through
     * here.
     *
     * @return the applied (clamped) value
     */
    public static int setRatePercent(int percent) {
        int clamped = Math.max(MIN_RATE_PERCENT, Math.min(MAX_RATE_PERCENT, percent));
        ratePercent = clamped;
        StormPerformanceSandboxMetrics.setHutchDirtRatePercent(clamped);
        return clamped;
    }

    public static int getRatePercent() {
        return ratePercent;
    }

    /**
     * Pure math: the {@code Rand.NextBool} inverse probability for one game-hour dirt roll.
     *
     * <p>Base is {@code doMeta}'s {@code min(25 - animals, 10)}, floored at 1 so overcrowded
     * hutches (24+ animals) stay a certainty rather than dividing by zero. The rate percent then
     * scales it: 100 leaves the base untouched, 200 halves the inverse probability (twice as
     * dirty), 50 doubles it (half as dirty).
     *
     * @param animalCount animals inside + outside the hutch
     * @param ratePercent {@code Storm.HutchDirtRatePercent}, must be &gt; 0
     * @return inverse probability for {@code Rand.NextBool}, always &gt;= 1
     */
    public static int effectiveProb(int animalCount, int ratePercent) {
        int base = 25 - animalCount;
        if (base > 10) {
            base = 10;
        }
        if (base < 1) {
            base = 1;
        }
        long prob = Math.round(base * 100.0 / ratePercent);
        return prob < 1 ? 1 : (int) prob;
    }

    /**
     * Entry driver for {@code IsoHutch.update()}: captures the pre-update dirt values and hour
     * marker. Parameter typed {@code Object} so the inlined advice does not embed a checkcast
     * against a game class (see the {@code feedback_elided_cast_load} memory).
     */
    public static void beforeUpdate(Object hutchRef) {
        if (broken) {
            return;
        }
        try {
            IsoHutch hutch = (IsoHutch) hutchRef;
            preHutchDirt = hutch.getHutchDirt();
            preNestBoxDirt = hutch.getNestBoxDirt();
            preLastHourCheck = hutch.lastHourCheck;
            armed = true;
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Exit driver for {@code IsoHutch.update()}: reverts whatever the vanilla per-tick rolls added
     * this tick, then applies Storm's game-hour accrual. Only the authoritative master-tile hutch
     * simulates ({@code isOwner()} / {@code !isSlave()} — the same gates the vanilla body uses);
     * when the vanilla body early-returned, {@code lastHourCheck} is unchanged and the dirt deltas
     * are zero, so this degrades to a no-op.
     */
    public static void afterUpdate(Object hutchRef) {
        if (broken || !armed) {
            return;
        }
        armed = false;
        try {
            IsoHutch hutch = (IsoHutch) hutchRef;
            if (hutch.isSlave() || !hutch.isOwner()) {
                return;
            }
            float newHutchDirt = preHutchDirt;
            float newNestBoxDirt = preNestBoxDirt;
            int percent = ratePercent;
            boolean hourGrow = hutch.lastHourCheck != preLastHourCheck;
            if (hourGrow && percent > 0) {
                int animals = hutch.animalInside.size() + hutch.animalOutside.size();
                if (animals > 0) {
                    int prob = effectiveProb(animals, percent);
                    if (Rand.NextBool(prob)) {
                        newHutchDirt = Math.min(newHutchDirt + 1.0F, 100.0F);
                    }
                    if (Rand.NextBool(prob)) {
                        newNestBoxDirt = Math.min(newNestBoxDirt + 1.0F, 100.0F);
                    }
                }
            }
            boolean changed = false;
            if (hutch.getHutchDirt() != newHutchDirt) {
                hutch.setHutchDirt(newHutchDirt);
                changed = true;
            }
            if (hutch.getNestBoxDirt() != newNestBoxDirt) {
                hutch.setNestBoxDirt(newNestBoxDirt);
                changed = true;
            }
            if (changed) {
                // Re-sync so clients that saw a vanilla-roll sync() this tick converge on the
                // reverted/re-rolled values.
                hutch.sync();
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void fail(Throwable t) {
        broken = true;
        LOGGER.error("Storm: hutch dirt rate fix failed; reverting to vanilla per-tick accrual", t);
    }
}
