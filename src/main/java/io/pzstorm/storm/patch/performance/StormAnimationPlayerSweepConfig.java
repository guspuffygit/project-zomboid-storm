package io.pzstorm.storm.patch.performance;

/**
 * Runtime gate and thresholds for Storm's orphaned-{@code AnimationPlayer} sweep (see {@link
 * StormAnimationPlayerSweep}).
 *
 * <p>On by default: it repairs an unbounded vanilla leak with no recovery path short of a restart,
 * and when nothing is orphaned it costs one array copy plus a set lookup per held player. {@code
 * -Dstorm.animplayer.sweep=false} restores vanilla behavior.
 *
 * <p>{@code -Dstorm.animplayer.sweep.graceMs=<n>} (default {@value #DEFAULT_GRACE_MS}) is how long
 * a character must have been continuously out of the world before its animation player is
 * reclaimed. Vanilla's own deferred-removal windows are 5s, so the default is an order of magnitude
 * more patient: a character that is virtualized and re-realized inside the window is never touched.
 * The floor of {@value #MIN_GRACE_MS} keeps a mistyped override from reclaiming inside vanilla's
 * own window.
 *
 * <p>{@code -Dstorm.animplayer.sweep.intervalMs=<n>} (default {@value #DEFAULT_INTERVAL_MS}) is the
 * minimum wall-clock gap between sweeps. {@code EveryOneMinuteEvent} is an in-game minute, which is
 * a couple of real seconds at default day length; this decouples the sweep from world speed.
 */
public final class StormAnimationPlayerSweepConfig {

    private static final long DEFAULT_GRACE_MS = 60_000L;

    /** Vanilla's own deferred-removal window; reclaiming faster than this would race it. */
    private static final long MIN_GRACE_MS = 5_000L;

    private static final long DEFAULT_INTERVAL_MS = 30_000L;

    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("storm.animplayer.sweep"));

    private static final long GRACE_MS =
            Math.max(
                    MIN_GRACE_MS, Long.getLong("storm.animplayer.sweep.graceMs", DEFAULT_GRACE_MS));

    private static final long INTERVAL_MS =
            Long.getLong("storm.animplayer.sweep.intervalMs", DEFAULT_INTERVAL_MS);

    private StormAnimationPlayerSweepConfig() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static long graceMs() {
        return GRACE_MS;
    }

    public static long intervalMs() {
        return INTERVAL_MS;
    }
}
