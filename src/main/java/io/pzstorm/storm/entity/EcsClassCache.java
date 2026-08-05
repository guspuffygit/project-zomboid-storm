package io.pzstorm.storm.entity;

import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.EcsClassCacheMetrics;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import zombie.characters.ecs.ECSComponent;

/**
 * Server-only memoization of the static {@code ECSComponent.getECSClass(Class)} superclass walk,
 * wired in by {@code EcsGetClassCachePatch}.
 *
 * <p>Vanilla walks {@code Class.getSuperclass()} up to {@code ECSComponent} on every call, and the
 * method sits on the component-query path ({@code ECSEntity.getECSComponent} / {@code
 * tryGetECSComponent} / {@code hasECSComponent}) — driven by {@code getStateMachine()} (57 call
 * sites) and per-character AIComponent lookups it runs millions of times per second on a busy
 * server (~2.9% self time live-profiled at 79 players, plus a chunk of {@code
 * ConcurrentHashMap.get} self time from the map lookups it feeds).
 *
 * <p>The function is pure — its result depends only on the argument's static superclass chain — so
 * memoization is outcome-identical. The cache is a {@link ClassValue}: JVM-optimized per-{@code
 * Class} storage, weakly referenced (no classloader pinning), with {@code computeValue} encoding
 * exactly the vanilla walk ({@code for (c = clazz; c != null && c != ECSComponent.class; c =
 * c.getSuperclass()) found = c; return found}). Edge inputs are handled before the cache so the
 * observable result always matches vanilla:
 *
 * <ul>
 *   <li>{@code null} — vanilla returns {@code null} (loop never entered). The helper returns {@code
 *       null}, which makes the advice fall through to the vanilla body ({@code
 *       ClassValue.get(null)} would NPE).
 *   <li>{@code ECSComponent.class} itself — vanilla returns {@code null} (loop condition fails
 *       immediately). The helper returns {@code null} → vanilla body runs → {@code null}.
 *   <li>Any other class (direct subclass, deep subclass, a class outside the hierarchy, an
 *       interface, a primitive) — {@code computeValue} runs the identical loop, so the cached value
 *       is the vanilla result by construction.
 * </ul>
 *
 * <p>Hit-path cost is a volatile read plus {@code ClassValue.get} — deliberately no metrics. The
 * only counter is a non-atomic miss tally inside {@code computeValue}, which runs once per distinct
 * class (see {@link EcsClassCacheMetrics}).
 *
 * <p>Kill switch: the {@code Storm.EcsClassCache} sandbox option (boolean, default {@code true},
 * live-appliable via admin sandbox push — the cache is stateless toward the game, so flipping in
 * either direction is always safe). The cache also permanently reverts to vanilla if a lookup ever
 * throws.
 */
public final class EcsClassCache {

    /** Default for {@code Storm.EcsClassCache}: memoization on. */
    public static final boolean DEFAULT_ENABLED = true;

    /**
     * Kill switch, driven by the {@code Storm.EcsClassCache} sandbox option through {@link
     * #setEnabled(boolean)}. Volatile because the sandbox applier may push updates from outside the
     * main thread; the per-call read is a single volatile load.
     */
    private static volatile boolean enabled = DEFAULT_ENABLED;

    /** Permanent revert-to-vanilla latch; set on the first {@link Throwable} out of a lookup. */
    private static boolean failed;

    /**
     * Distinct classes memoized ({@code computeValue} executions). Non-atomic on purpose: written
     * once per class (barring a benign first-use race), read dirty at scrape time.
     */
    public static long misses;

    private static final ClassValue<Class<?>> CACHE =
            new ClassValue<>() {
                @Override
                protected Class<?> computeValue(Class<?> type) {
                    misses++;
                    // Exactly the vanilla ECSComponent.getECSClass(Class) walk.
                    Class<?> foundEcsClass = null;
                    for (Class<?> c = type;
                            c != null && c != ECSComponent.class;
                            c = c.getSuperclass()) {
                        foundEcsClass = c;
                    }
                    return foundEcsClass;
                }
            };

    static {
        EcsClassCacheMetrics.ensureStarted();
    }

    private EcsClassCache() {}

    /**
     * Applies the {@code Storm.EcsClassCache} sandbox option ({@code false} = vanilla walk, {@code
     * true} = memoized) and pushes the applied value to the Prometheus gauge. Single mutation point
     * — sandbox apply and tests both funnel through here.
     *
     * @return the applied value
     */
    public static boolean setEnabled(boolean value) {
        enabled = value;
        StormPerformanceSandboxMetrics.setEcsClassCache(value);
        return value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the memoized {@code getECSClass} result for {@code clazz}, or {@code null} to make
     * the advice fall through to the vanilla walk (kill switch off, failure latch, {@code null}
     * input, or an input whose vanilla result is {@code null} — outcome-identical either way,
     * because the vanilla body computes {@code null} for exactly those inputs).
     *
     * @param clazz the {@code Class} argument ({@code Class} is a JDK type, so the advice referring
     *     to it cannot early-load the transform target)
     */
    public static Class<?> lookup(Class<?> clazz) {
        if (failed || !enabled) {
            return null;
        }
        if (clazz == null || clazz == ECSComponent.class) {
            // Vanilla returns null for both; returning null here routes them to the vanilla
            // body, which is trivially cheap for these inputs (the loop never iterates).
            return null;
        }
        try {
            return CACHE.get(clazz);
        } catch (Throwable t) {
            failed = true;
            StormLogger.LOGGER.error(
                    "EcsClassCache lookup failed — reverting to vanilla"
                            + " ECSComponent.getECSClass walk",
                    t);
            return null;
        }
    }
}
