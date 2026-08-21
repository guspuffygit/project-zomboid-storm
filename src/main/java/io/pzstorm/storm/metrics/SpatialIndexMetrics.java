package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.prometheus.metrics.core.metrics.Histogram;

/**
 * Instrumentation for the per-tick {@code StormSpatialIndex} rebuild. Writers are main-thread only
 * (the rebuild runs inside {@code MovingObjectUpdateScheduler.startFrame}); counters are plain
 * {@code long}s read dirty at scrape time, same discipline as {@link PlayerLosFastPathMetrics}.
 */
public final class SpatialIndexMetrics {

    public static long rebuilds;
    public static long failedRebuilds;
    public static long lastObjects;
    public static long lastBuckets;

    private static final Histogram REBUILD_DURATION =
            Histogram.builder()
                    .name("storm_spatial_index_rebuild_duration_seconds")
                    .help(
                            "Time spent rebuilding the per-tick chunk-bucket index of all moving"
                                    + " objects in MovingObjectUpdateScheduler.startFrame.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback REBUILDS =
            CounterWithCallback.builder()
                    .name("storm_spatial_index_rebuilds_total")
                    .help(
                            "Per-tick spatial-index rebuilds by outcome: ok = snapshot published;"
                                    + " failed = rebuild threw, consumers fell back to full scans"
                                    + " for that tick.")
                    .labelNames("outcome")
                    .callback(
                            callback -> {
                                callback.call((double) rebuilds, "ok");
                                callback.call((double) failedRebuilds, "failed");
                            })
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final GaugeWithCallback SIZE =
            GaugeWithCallback.builder()
                    .name("storm_spatial_index_objects")
                    .help("Moving objects captured by the most recent spatial-index rebuild.")
                    .callback(callback -> callback.call((double) lastObjects))
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final GaugeWithCallback BUCKETS =
            GaugeWithCallback.builder()
                    .name("storm_spatial_index_buckets")
                    .help("Non-empty chunk buckets in the most recent spatial-index rebuild.")
                    .callback(callback -> callback.call((double) lastBuckets))
                    .register(StormPrometheus.registry());

    private SpatialIndexMetrics() {}

    public static void recordRebuild(long nanos, int objects, int buckets) {
        rebuilds++;
        lastObjects = objects;
        lastBuckets = buckets;
        REBUILD_DURATION.observe(nanos / 1e9);
    }

    public static void recordFailure() {
        failedRebuilds++;
    }
}
