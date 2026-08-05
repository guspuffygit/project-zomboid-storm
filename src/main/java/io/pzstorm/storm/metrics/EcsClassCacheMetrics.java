package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.pzstorm.storm.entity.EcsClassCache;

/**
 * Miss tally for the {@code EcsClassCache} memoization of {@code ECSComponent.getECSClass(Class)}.
 *
 * <p>Deliberately callback-only and hit-path-free: the memoized path runs millions of times per
 * second, so it records nothing. The miss counter is a plain non-atomic {@code long} written inside
 * {@code ClassValue.computeValue} — once per distinct class (barring a benign first-use race, where
 * {@code ClassValue} may invoke {@code computeValue} concurrently and discard all but one result) —
 * and read dirty at scrape time.
 *
 * <p>Loaded from {@code EcsClassCache}'s static initializer ({@code ensureStarted()}), which runs
 * on the first advised {@code getECSClass} call, so the counter registers as soon as the patch is
 * live.
 */
public final class EcsClassCacheMetrics {

    @SuppressWarnings("unused")
    private static final CounterWithCallback MISSES =
            CounterWithCallback.builder()
                    .name("storm_ecs_class_cache_misses_total")
                    .help(
                            "Distinct classes memoized by EcsClassCache (ClassValue computeValue"
                                    + " executions). Every other getECSClass call is a cache hit;"
                                    + " hits are intentionally not counted.")
                    .callback(callback -> callback.call((double) EcsClassCache.misses))
                    .register(StormPrometheus.registry());

    private EcsClassCacheMetrics() {}

    /** No-op whose side effect is triggering this class's static initialization. */
    public static void ensureStarted() {}
}
