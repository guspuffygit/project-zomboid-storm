package io.pzstorm.storm.patch.networking;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.utils.UpdateLimit;

/**
 * Allows the dedicated server's main-loop tick rate to be configured via the {@code
 * storm.server.tickIntervalMs} system property. The vanilla server hard-codes the tick gate to a
 * 100&nbsp;ms {@link UpdateLimit} (10&nbsp;TPS) inside {@code GameServer.main()}; this patch
 * substitutes that constructor call with a factory that reads the property and clamps to a safe
 * range.
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

    public static final String TICK_INTERVAL_PROPERTY = "storm.server.tickIntervalMs";

    /** Vanilla server tick interval — 10 TPS. */
    public static final long DEFAULT_TICK_INTERVAL_MS = 100L;

    /** 0ms floor — no gating, server ticks as fast as the loop can run. */
    public static final long MIN_TICK_INTERVAL_MS = 0L;

    /** 1 TPS lower bound (slower than vanilla, useful for low-power servers). */
    public static final long MAX_TICK_INTERVAL_MS = 1000L;

    /**
     * Sentinel value for {@link #TICK_INTERVAL_PROPERTY} that enables player-count-driven auto
     * mode: {@link #AUTO_LOW_LOAD_INTERVAL_MS} below {@link #AUTO_PLAYER_THRESHOLD} players, {@link
     * #AUTO_HIGH_LOAD_INTERVAL_MS} at or above it.
     */
    public static final String AUTO_PROPERTY_VALUE = "auto";

    /** Player count at which auto mode switches from low- to high-load interval. */
    public static final int AUTO_PLAYER_THRESHOLD = 32;

    /**
     * Auto-mode interval used while online player count is below {@link #AUTO_PLAYER_THRESHOLD}.
     */
    public static final long AUTO_LOW_LOAD_INTERVAL_MS = 30L;

    /** Auto-mode interval used while online player count is &ge; {@link #AUTO_PLAYER_THRESHOLD}. */
    public static final long AUTO_HIGH_LOAD_INTERVAL_MS = 0L;

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
        private static volatile boolean autoMode;
        private static long observedTicks;
        private static long windowStartNanos = System.nanoTime();

        /** Window in nanoseconds for the average-TPS log line. Package-private for tests. */
        static long logWindowNanos = LOG_WINDOW_NANOS_DEFAULT;

        /**
         * Source of the live player count consulted by auto mode. Defaults to {@link
         * #countFullyConnectedPlayers()} so still-handshaking sessions don't inflate the load
         * estimate (vanilla {@code GameServer.getPlayerCount()} counts player slots as soon as
         * {@code playerIds[i]} is assigned, which happens before login/loading finishes). Returns
         * {@code 0} if the server isn't initialised yet so unit tests and pre-server-start calls
         * don't NPE. Tests overwrite this directly.
         */
        static IntSupplier playerCountSupplier =
                () -> {
                    try {
                        return countFullyConnectedPlayers();
                    } catch (Throwable t) {
                        return 0;
                    }
                };

        /**
         * Counts only player slots on connections where {@link
         * zombie.core.raknet.UdpConnection#isFullyConnected()} returns {@code true}. A connection
         * becomes fully connected at the end of the login/world-load handshake (see {@code
         * UdpConnection.setFullyConnected()}); before that point the player isn't actually ticking
         * in the simulation and shouldn't drive the auto-mode load estimate.
         */
        public static int countFullyConnectedPlayers() {
            zombie.core.raknet.UdpEngine engine = zombie.network.GameServer.udpEngine;
            if (engine == null || engine.connections == null) {
                return 0;
            }
            int count = 0;
            for (int n = 0; n < engine.connections.size(); n++) {
                zombie.core.raknet.UdpConnection c = engine.connections.get(n);
                if (c == null || !c.isFullyConnected()) {
                    continue;
                }
                for (int playerIndex = 0; playerIndex < c.playerIds.length; playerIndex++) {
                    if (c.playerIds[playerIndex] != -1) {
                        count++;
                    }
                }
            }
            return count;
        }

        public static UpdateLimit create(long defaultDelayMs) {
            if (defaultDelayMs != DEFAULT_TICK_INTERVAL_MS) {
                return new UpdateLimit(defaultDelayMs);
            }
            if (isAutoModeRequested()) {
                long initial = AUTO_LOW_LOAD_INTERVAL_MS;
                LOGGER.info(
                        "Storm: server tick interval = auto (start={}ms; <{} players={}ms,"
                                + " >={} players={}ms) [override via -D{}=auto]",
                        initial,
                        AUTO_PLAYER_THRESHOLD,
                        AUTO_LOW_LOAD_INTERVAL_MS,
                        AUTO_PLAYER_THRESHOLD,
                        AUTO_HIGH_LOAD_INTERVAL_MS,
                        TICK_INTERVAL_PROPERTY);
                UpdateLimit limit = new UpdateLimit(initial);
                serverTickLimiter = limit;
                currentTickIntervalMs = initial;
                autoMode = true;
                observedTicks = 0;
                windowStartNanos = System.nanoTime();
                return limit;
            }
            long resolved = resolveTickIntervalMs();
            if (resolved == DEFAULT_TICK_INTERVAL_MS) {
                LOGGER.info(
                        "Storm: server tick interval = {}ms (~{} TPS) [vanilla]",
                        resolved,
                        formatTps(resolved));
            } else {
                LOGGER.info(
                        "Storm: server tick interval = {}ms (~{} TPS) [override via -D{}]",
                        resolved,
                        formatTps(resolved),
                        TICK_INTERVAL_PROPERTY);
            }
            UpdateLimit limit = new UpdateLimit(resolved);
            serverTickLimiter = limit;
            currentTickIntervalMs = resolved;
            autoMode = false;
            observedTicks = 0;
            windowStartNanos = System.nanoTime();
            return limit;
        }

        /** True if {@link #TICK_INTERVAL_PROPERTY} is set to {@link #AUTO_PROPERTY_VALUE}. */
        public static boolean isAutoModeRequested() {
            String prop = System.getProperty(TICK_INTERVAL_PROPERTY);
            return prop != null && AUTO_PROPERTY_VALUE.equalsIgnoreCase(prop.trim());
        }

        /** True while the live limiter is being driven by auto mode. */
        public static boolean isAutoModeActive() {
            return autoMode;
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
            boolean wasAuto = autoMode;
            autoMode = false;
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
            if (wasAuto) {
                LOGGER.info("Storm: explicit setTickIntervalMs disables auto mode");
            }
            observedTicks = 0;
            windowStartNanos = System.nanoTime();
            LOGGER.info(
                    "Storm: server tick interval updated to {}ms (~{} TPS)",
                    applied,
                    formatTps(applied));
            return applied;
        }

        /**
         * Enable auto mode at runtime. Engages the player-count-driven scheduler immediately and
         * applies the appropriate interval based on the current player count. Returns the interval
         * just applied. Throws {@link IllegalStateException} if the limiter has not been installed
         * yet.
         */
        public static long enableAutoMode() {
            UpdateLimit limit = serverTickLimiter;
            if (limit == null) {
                throw new IllegalStateException(
                        "Server tick limiter not yet initialized; cannot enable auto mode");
            }
            int players = playerCountSupplier.getAsInt();
            long target =
                    players >= AUTO_PLAYER_THRESHOLD
                            ? AUTO_HIGH_LOAD_INTERVAL_MS
                            : AUTO_LOW_LOAD_INTERVAL_MS;
            limit.setUpdatePeriod(target);
            currentTickIntervalMs = target;
            observedTicks = 0;
            windowStartNanos = System.nanoTime();
            autoMode = true;
            LOGGER.info(
                    "Storm: auto mode enabled — tick interval = {}ms (~{} TPS), {} player(s)",
                    target,
                    formatTps(target),
                    players);
            return target;
        }

        /**
         * If auto mode is active, recompute the desired interval from the live player count and
         * apply it to the limiter when it changes. No-op outside auto mode or before the limiter is
         * installed. Package-private so tests can drive it directly.
         */
        static void recomputeAutoInterval() {
            if (!autoMode) {
                return;
            }
            UpdateLimit limit = serverTickLimiter;
            if (limit == null) {
                return;
            }
            int players = playerCountSupplier.getAsInt();
            long target =
                    players >= AUTO_PLAYER_THRESHOLD
                            ? AUTO_HIGH_LOAD_INTERVAL_MS
                            : AUTO_LOW_LOAD_INTERVAL_MS;
            if (target == currentTickIntervalMs) {
                return;
            }
            limit.setUpdatePeriod(target);
            currentTickIntervalMs = target;
            observedTicks = 0;
            windowStartNanos = System.nanoTime();
            LOGGER.info(
                    "Storm: auto mode adjusted tick interval to {}ms (~{} TPS) — {} player(s)",
                    target,
                    formatTps(target),
                    players);
        }

        /**
         * Drop-in replacement for {@code UpdateLimit.Check()} when called from {@code
         * GameServer.main}. Delegates to the original {@link UpdateLimit#Check()} and, for the
         * server tick limiter only, accumulates a tick count and emits an average-TPS log line once
         * per {@link #logWindowNanos}.
         */
        public static boolean checkAndCount(UpdateLimit limit) {
            if (autoMode && limit == serverTickLimiter) {
                recomputeAutoInterval();
            }
            boolean shouldTick = limit.Check();
            if (shouldTick && limit == serverTickLimiter) {
                observedTicks++;
                long now = System.nanoTime();
                long elapsed = now - windowStartNanos;
                if (elapsed >= logWindowNanos) {
                    double seconds = elapsed / 1_000_000_000.0;
                    double tps = observedTicks / seconds;
                    LOGGER.info(
                            "Storm: avg server TPS over last {}s = {} ({} ticks)",
                            String.format("%.1f", seconds),
                            String.format("%.2f", tps),
                            observedTicks);
                    observedTicks = 0;
                    windowStartNanos = now;
                }
            }
            return shouldTick;
        }

        /**
         * Reads {@link #TICK_INTERVAL_PROPERTY}, clamping to {@link #MIN_TICK_INTERVAL_MS}..{@link
         * #MAX_TICK_INTERVAL_MS}. Returns {@link #DEFAULT_TICK_INTERVAL_MS} when the property is
         * unset or unparseable.
         */
        public static long resolveTickIntervalMs() {
            String prop = System.getProperty(TICK_INTERVAL_PROPERTY);
            if (prop == null || prop.isEmpty()) {
                return DEFAULT_TICK_INTERVAL_MS;
            }
            if (AUTO_PROPERTY_VALUE.equalsIgnoreCase(prop.trim())) {
                return DEFAULT_TICK_INTERVAL_MS;
            }
            long parsed;
            try {
                parsed = Long.parseLong(prop.trim());
            } catch (NumberFormatException e) {
                LOGGER.warn(
                        "Storm: invalid -D{}=\"{}\", falling back to vanilla {}ms",
                        TICK_INTERVAL_PROPERTY,
                        prop,
                        DEFAULT_TICK_INTERVAL_MS);
                return DEFAULT_TICK_INTERVAL_MS;
            }
            if (parsed < MIN_TICK_INTERVAL_MS) {
                LOGGER.warn(
                        "Storm: -D{}={} below floor, clamping to {}ms",
                        TICK_INTERVAL_PROPERTY,
                        parsed,
                        MIN_TICK_INTERVAL_MS);
                return MIN_TICK_INTERVAL_MS;
            }
            if (parsed > MAX_TICK_INTERVAL_MS) {
                LOGGER.warn(
                        "Storm: -D{}={} above ceiling, clamping to {}ms",
                        TICK_INTERVAL_PROPERTY,
                        parsed,
                        MAX_TICK_INTERVAL_MS);
                return MAX_TICK_INTERVAL_MS;
            }
            return parsed;
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
            autoMode = false;
        }

        private static String formatTps(long intervalMs) {
            return intervalMs <= 0 ? "unbounded" : Long.toString(1000L / intervalMs);
        }
    }
}
