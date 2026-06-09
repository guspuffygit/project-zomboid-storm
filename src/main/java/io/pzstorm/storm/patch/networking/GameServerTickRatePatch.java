package io.pzstorm.storm.patch.networking;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.utils.UpdateLimit;
import zombie.network.GameServer;

/**
 * Allows the dedicated server's main-loop tick rate to be configured. The vanilla server hard-codes
 * the tick gate to a 100&nbsp;ms {@link UpdateLimit} (10&nbsp;TPS) inside {@code
 * GameServer.main()}; this patch substitutes that constructor call with a factory that installs an
 * {@link UpdateLimit} the sandbox applier can retune via {@link
 * UpdateLimitFactory#setTickIntervalMs(long)}. The {@code Storm.ServerFps} sandbox option drives
 * this via {@link ServerFpsConfig#applyUnifiedFps(int)} alongside the other two fps controllers.
 *
 * <p>The patch also wraps the in-loop {@code UpdateLimit.Check()} calls in {@code main} with a
 * counting helper that logs the observed average server TPS once per minute. The helper is invoked
 * for every {@code Check()} call in {@code main}, but it only counts ticks for the server tick
 * limiter (filtered by reference identity against the instance produced by {@link
 * UpdateLimitFactory#create(long)}); the static-field limiters (world map, relevance) pass through.
 *
 * <p>Other {@code new UpdateLimit(long)} sites in {@code GameServer} (e.g. the static field
 * initializers for the world-map / relevance limiters) are <b>not</b> touched, because the
 * substitution is scoped to invocations occurring inside the {@code main(String[])} method body.
 */
public class GameServerTickRatePatch extends StormClassTransformer {

    /** Vanilla server tick interval — 10 TPS. */
    public static final long DEFAULT_TICK_INTERVAL_MS = 100L;

    /** 4 ms floor — matches {@link ServerFpsConfig#MAX_FPS} = 240 (round(1000/240) = 4). */
    public static final long MIN_TICK_INTERVAL_MS = 4L;

    /** 1 TPS lower bound (slower than vanilla, useful for low-power servers). */
    public static final long MAX_TICK_INTERVAL_MS = 1000L;

    public GameServerTickRatePatch() {
        super("zombie.network.GameServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        try {
            ElementMatcher.Junction<MethodDescription> mainMatcher =
                    ElementMatchers.named("main")
                            .and(ElementMatchers.takesArgument(0, String[].class));
            return builder.visit(
                            MemberSubstitution.relaxed()
                                    .constructor(
                                            ElementMatchers.isDeclaredBy(UpdateLimit.class)
                                                    .and(
                                                            ElementMatchers.takesArguments(
                                                                    long.class)))
                                    .replaceWith(
                                            UpdateLimitFactory.class.getDeclaredMethod(
                                                    "create", long.class))
                                    .on(mainMatcher))
                    .visit(
                            MemberSubstitution.relaxed()
                                    .method(
                                            ElementMatchers.isDeclaredBy(UpdateLimit.class)
                                                    .and(ElementMatchers.named("Check"))
                                                    .and(ElementMatchers.takesArguments(0)))
                                    .replaceWith(
                                            UpdateLimitFactory.class.getDeclaredMethod(
                                                    "checkAndCount", UpdateLimit.class))
                                    .on(mainMatcher));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(
                    "Failed to setup MemberSubstitution for GameServer tick rate", e);
        }
    }

    /**
     * Replacement factory for {@code new UpdateLimit(long)} inside {@code GameServer.main}, plus
     * the wrapper invoked in place of {@code UpdateLimit.Check()} for in-loop tick counting.
     *
     * <p>Only the server-tick-limiter call site (vanilla delay = {@value #DEFAULT_TICK_INTERVAL_MS}
     * ms) is adjusted in {@link #create(long)}; any other delay value is passed through unchanged
     * so future game updates that add new {@code UpdateLimit}s inside {@code main} are not silently
     * rewritten.
     *
     * <p>{@link #checkAndCount(UpdateLimit)} is invoked for every {@code UpdateLimit.Check()} call
     * in {@code main}, but only counts ticks for the instance produced by {@link #create(long)} —
     * the static-field limiters (world map, relevance) are filtered out by identity.
     */
    public static class UpdateLimitFactory {

        private static final long LOG_WINDOW_NANOS_DEFAULT = TimeUnit.MINUTES.toNanos(1);

        private static volatile UpdateLimit serverTickLimiter;
        private static volatile long currentTickIntervalMs = DEFAULT_TICK_INTERVAL_MS;
        private static long observedTicks;
        private static long windowStartNanos = System.nanoTime();

        /** Window in nanoseconds for the average-TPS log line. Package-private for tests. */
        static long logWindowNanos = LOG_WINDOW_NANOS_DEFAULT;

        public static UpdateLimit create(long defaultDelayMs) {
            if (defaultDelayMs != DEFAULT_TICK_INTERVAL_MS) {
                return new UpdateLimit(defaultDelayMs);
            }
            UpdateLimit limit = new UpdateLimit(DEFAULT_TICK_INTERVAL_MS);
            serverTickLimiter = limit;
            currentTickIntervalMs = DEFAULT_TICK_INTERVAL_MS;
            observedTicks = 0;
            windowStartNanos = System.nanoTime();
            StormPerformanceSandboxMetrics.setServerTickIntervalMs(DEFAULT_TICK_INTERVAL_MS);
            return limit;
        }

        /**
         * @return {@code true} once {@link #create(long)} has installed the server tick limiter.
         *     The sandbox applier checks this before calling {@link
         *     ServerFpsConfig#applyUnifiedFps(int)} to avoid an {@link IllegalStateException} on
         *     the boot path where {@code OnServerStartedEvent} fires before the limiter exists.
         */
        public static boolean isLimiterReady() {
            return serverTickLimiter != null;
        }

        /** Current effective tick interval in ms. */
        public static long getCurrentTickIntervalMs() {
            return currentTickIntervalMs;
        }

        /**
         * Live-updates the server tick interval, clamping to {@link #MIN_TICK_INTERVAL_MS}..{@link
         * #MAX_TICK_INTERVAL_MS}. Returns the value actually applied. Throws {@link
         * IllegalStateException} if the server tick limiter has not been installed yet (i.e. before
         * {@code GameServer.main} has run the patched constructor).
         */
        public static long setTickIntervalMs(long requestedMs) {
            UpdateLimit limit = serverTickLimiter;
            if (limit == null) {
                throw new IllegalStateException(
                        "Server tick limiter not yet initialized; cannot set interval");
            }
            long applied = requestedMs;
            if (applied < MIN_TICK_INTERVAL_MS) {
                LOGGER.warn(
                        "Storm: tick interval {}ms below floor, clamping to {}ms",
                        requestedMs,
                        MIN_TICK_INTERVAL_MS);
                applied = MIN_TICK_INTERVAL_MS;
            } else if (applied > MAX_TICK_INTERVAL_MS) {
                LOGGER.warn(
                        "Storm: tick interval {}ms above ceiling, clamping to {}ms",
                        requestedMs,
                        MAX_TICK_INTERVAL_MS);
                applied = MAX_TICK_INTERVAL_MS;
            }
            limit.setUpdatePeriod(applied);
            currentTickIntervalMs = applied;
            observedTicks = 0;
            windowStartNanos = System.nanoTime();
            StormPerformanceSandboxMetrics.setServerTickIntervalMs(applied);
            LOGGER.info(
                    "Storm: server tick interval updated to {}ms (~{} TPS)",
                    applied,
                    formatTps(applied));
            return applied;
        }

        /**
         * Drop-in replacement for {@code UpdateLimit.Check()} when called from {@code
         * GameServer.main}. Delegates to the original {@link UpdateLimit#Check()} and, for the
         * server tick limiter only, accumulates a tick count and emits an average-TPS log line once
         * per {@link #logWindowNanos}.
         */
        public static boolean checkAndCount(UpdateLimit limit) {
            boolean shouldTick = limit.Check();
            if (shouldTick && limit == serverTickLimiter) {
                observedTicks++;
                long now = System.nanoTime();
                long elapsed = now - windowStartNanos;
                if (elapsed >= logWindowNanos) {
                    double seconds = elapsed / 1_000_000_000.0;
                    double tps = observedTicks / seconds;
                    LOGGER.info(
                            "Storm: avg server TPS over last {}s = {} ({} ticks, {} players)",
                            String.format("%.1f", seconds),
                            String.format("%.2f", tps),
                            observedTicks,
                            GameServer.Players.size());
                    observedTicks = 0;
                    windowStartNanos = now;
                }
            }
            return shouldTick;
        }

        /** Test-only — resets the per-window tick counter and re-anchors the window. */
        static void resetTickCounterForTest() {
            observedTicks = 0;
            windowStartNanos = System.nanoTime();
        }

        /** Test-only — current tick count within the live window. */
        static long observedTicksForTest() {
            return observedTicks;
        }

        /** Test-only — current installed server tick limiter (or {@code null}). */
        static UpdateLimit serverTickLimiterForTest() {
            return serverTickLimiter;
        }

        /** Test-only — clears the installed server tick limiter reference. */
        static void clearServerTickLimiterForTest() {
            serverTickLimiter = null;
            currentTickIntervalMs = DEFAULT_TICK_INTERVAL_MS;
        }

        private static String formatTps(long intervalMs) {
            return Long.toString(1000L / intervalMs);
        }
    }
}
