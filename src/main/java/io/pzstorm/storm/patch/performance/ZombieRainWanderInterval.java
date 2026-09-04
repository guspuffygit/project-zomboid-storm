package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import io.pzstorm.storm.metrics.ZombieRainWanderMetrics;
import zombie.iso.objects.RainManager;
import zombie.network.GameServer;

/**
 * Server-side control over the one place in Build 42 where weather drives zombie simulation cost:
 * the idle wander interval, which vanilla shortens while it is raining.
 *
 * <h2>The vanilla behaviour, and why the polarity surprises people</h2>
 *
 * <p>{@code ZombieIdleState.pickRandomWanderInterval()} is:
 *
 * <pre>{@code
 * float f = Rand.Next(400, 1000);
 * if (!RainManager.isRaining()) {
 *     f *= 1.5F;
 * }
 * return f;
 * }</pre>
 *
 * <p>The penalty is on the <em>dry</em> branch, so rain does not lengthen the interval, it removes
 * a lengthening that dry weather gets. Dry is 600-1500 (mean 1050), raining is 400-1000 (mean 700):
 * every idle outdoor zombie re-picks a wander destination <b>1.5x as often</b> for the whole storm.
 * Each expiry calls {@code pathToLocation()} in {@code ZombieIdleState.execute} and pushes that
 * zombie from idle to moving, which costs a path solve, per-frame movement and collision, square
 * and chunk transitions, and a position stream to every client in range.
 *
 * <p>This is the only weather-to-server-simulation coupling in the build, and it is the one that
 * scales with {@code zombie count x loaded chunks}, i.e. with the largest workload a populated
 * server has. {@code RainManager.UpdateServer()} is an empty method body, thunder never touches
 * {@code WorldSoundManager}, and {@code getWeatherHearingMultiplier} makes zombies hear
 * <em>less</em> in rain, not more.
 *
 * <h2>What the option does</h2>
 *
 * <p>{@code Storm.ZombieRainWanderPercent} scales the interval returned <em>while it is
 * raining</em> and leaves the dry branch exactly as vanilla wrote it:
 *
 * <ul>
 *   <li>{@value #VANILLA_PERCENT} (default) is vanilla, bit for bit: the decision returns before it
 *       asks the weather anything.
 *   <li>{@value #RAIN_AS_DRY_PERCENT} makes the raining interval {@code Rand.Next(400, 1000) *
 *       1.5}, which is the dry distribution exactly, so rain stops costing anything at all.
 *   <li>Between the two, rain costs proportionally less; above it, zombies wander <em>less</em> in
 *       rain than in dry weather, which is not a vanilla behaviour and is offered only because the
 *       clamp has to stop somewhere.
 * </ul>
 *
 * <p>There is no ramp to soften: {@code ClimateManager.isRaining()} is {@code
 * getPrecipitationIntensity() > 0 && !getPrecipitationIsSnow()}, so the lightest drizzle trips the
 * vanilla branch at full strength and a snowstorm never trips it at all. Whatever this option is
 * set to applies from the first drop.
 *
 * <h2>Scope</h2>
 *
 * <p>Registered under {@code StormEnv.isStormServer()} and additionally gated on {@code
 * GameServer.server}, so a co-op host's client half keeps vanilla intervals. Zombies indoors and
 * zombies that are {@code isUseless()} never reach the wander branch at all, in vanilla or here.
 *
 * <h2>Failure latch</h2>
 *
 * <p>Any throwable inside the decision latches the scaling off for the rest of the boot and logs
 * once. The fail-safe direction is vanilla: a wrongly shortened interval is what the server already
 * does, a wrongly enormous one would strand every idle zombie standing still.
 */
public final class ZombieRainWanderInterval {

    /** Vanilla: the raining interval is used exactly as {@code Rand.Next(400, 1000)} gave it. */
    public static final int VANILLA_PERCENT = 100;

    /** Floor. Below vanilla would make rain cost <em>more</em>, which nobody wants. */
    public static final int MIN_PERCENT = 100;

    /** Ceiling. 3x the raining interval is already twice the dry one. */
    public static final int MAX_PERCENT = 300;

    /**
     * The value at which a raining interval matches the dry distribution exactly, because vanilla's
     * dry branch is {@code f *= 1.5F}. This is the setting that makes rain free.
     */
    public static final int RAIN_AS_DRY_PERCENT = 150;

    private static volatile int currentPercent = VANILLA_PERCENT;
    private static volatile boolean failureLatched;

    private ZombieRainWanderInterval() {}

    /** Percentage currently applied to the raining interval. {@value #VANILLA_PERCENT} is off. */
    public static int getCurrentPercent() {
        return currentPercent;
    }

    /** True once an unexpected error has restored vanilla intervals for this boot. */
    public static boolean isFailureLatched() {
        return failureLatched;
    }

    /** Pure: the request clamped to {@link #MIN_PERCENT}..{@link #MAX_PERCENT}. */
    public static int clampPercent(int requested) {
        if (requested < MIN_PERCENT) {
            LOGGER.warn(
                    "Storm: zombie rain wander percent {} below floor, clamping to {}",
                    requested,
                    MIN_PERCENT);
            return MIN_PERCENT;
        }
        if (requested > MAX_PERCENT) {
            LOGGER.warn(
                    "Storm: zombie rain wander percent {} above ceiling, clamping to {}",
                    requested,
                    MAX_PERCENT);
            return MAX_PERCENT;
        }
        return requested;
    }

    /**
     * Pure: the interval a raining pick should return. Kept separate from {@link #adjust} so the
     * arithmetic can be tested without a weather singleton.
     */
    public static float scale(float vanillaInterval, int percent) {
        return vanillaInterval * (percent / 100.0f);
    }

    /** Live-updates the percentage, clamping the request. Returns the value actually applied. */
    public static int setPercent(int requested) {
        int applied = clampPercent(requested);
        currentPercent = applied;
        StormPerformanceSandboxMetrics.setZombieRainWanderPercent(applied);
        if (applied == VANILLA_PERCENT) {
            LOGGER.info(
                    "Storm: zombie rain wander percent {} (vanilla; rain shortens idle wander"
                            + " intervals to 2/3 of the dry value)",
                    applied);
        } else if (applied == RAIN_AS_DRY_PERCENT) {
            LOGGER.info(
                    "Storm: zombie rain wander percent {} (rain now costs nothing; raining"
                            + " intervals match the dry distribution exactly)",
                    applied);
        } else {
            LOGGER.info("Storm: zombie rain wander percent updated to {}", applied);
        }
        return applied;
    }

    /**
     * The decision. Called from the exit advice on {@code
     * ZombieIdleState.pickRandomWanderInterval()} with vanilla's answer; returns the interval to
     * use instead.
     *
     * <p>Asks {@code RainManager.isRaining()} rather than inferring the branch from the value,
     * because the dry and raining distributions overlap on 600-1000 and no return value can be
     * attributed to a branch. The call is the same static the method itself just made, in the same
     * tick, so the two cannot disagree.
     */
    public static float adjust(float vanilla) {
        int percent = currentPercent;
        if (percent == VANILLA_PERCENT || failureLatched || !GameServer.server) {
            return vanilla;
        }
        try {
            if (!RainManager.isRaining()) {
                ZombieRainWanderMetrics.dry++;
                return vanilla;
            }
            ZombieRainWanderMetrics.rainScaled++;
            return scale(vanilla, percent);
        } catch (Throwable t) {
            latch(t);
            return vanilla;
        }
    }

    private static void latch(Throwable t) {
        failureLatched = true;
        LOGGER.error(
                "Storm: zombie rain wander scaling disabled for this boot after an unexpected"
                        + " error; vanilla idle wander intervals restored",
                t);
    }

    /** Test-only: back to a freshly booted state. */
    static void resetForTest() {
        currentPercent = VANILLA_PERCENT;
        failureLatched = false;
    }

    /** Test-only: drives the failure latch without needing something to actually throw. */
    static void latchForTest() {
        failureLatched = true;
    }
}
