package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;

/**
 * Metrics for the {@code IsoGridSquare.removeGlassAttachments} loop guard. Main-thread writer only
 * (window smashing runs on the game main loop on both JVMs).
 */
public final class GlassAttachmentRemovalGuardMetrics {

    private static long failures;

    @SuppressWarnings("unused")
    private static final CounterWithCallback FAILURES =
            CounterWithCallback.builder()
                    .name("storm_glass_attachment_removal_failures_total")
                    .help(
                            "Objects that IsoGridSquare.removeGlassAttachments tried to remove"
                                    + " from a smashed window's square but RemoveTileObject left in"
                                    + " place. Vanilla re-examines the same object forever and"
                                    + " pins the main thread at 0 TPS; Storm skips it instead."
                                    + " Each increment is one such object, logged at WARN.")
                    .callback(callback -> callback.call((double) failures))
                    .register(StormPrometheus.registry());

    private GlassAttachmentRemovalGuardMetrics() {}

    public static void recordFailure() {
        failures++;
    }
}
