package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;

/**
 * Prometheus instruments for {@code io.pzstorm.storm.connection.LoginQueueEarlyRelease}.
 *
 * <ul>
 *   <li>{@code storm_login_queue_early_release_total} — login-queue slots freed at the WorldMap
 *       request instead of at {@code LoginQueueDone}.
 *   <li>{@code storm_login_queue_early_release_capped_total} — releases skipped because the {@code
 *       -Dstorm.loginQueueMaxConcurrentLoaders} ceiling was reached; the slot was held vanilla-long
 *       instead.
 *   <li>{@code storm_login_queue_concurrent_loaders} — joiners currently in the
 *       released-but-still-loading phase.
 *   <li>{@code storm_login_queue_reclaimed_seconds} — per released join, the time between freeing
 *       the slot and that client's {@code LoginQueueDone}: queue serialization reclaimed that
 *       vanilla would have spent holding the slot through client-local loading.
 * </ul>
 */
public final class LoginQueueEarlyReleaseMetrics {

    private static final Counter RELEASED =
            Counter.builder()
                    .name("storm_login_queue_early_release_total")
                    .help(
                            "Login-queue slots freed when the joiner's WorldMap download request"
                                    + " arrived (last server round-trip) instead of at"
                                    + " LoginQueueDone.")
                    .register(StormPrometheus.registry());

    private static final Counter CAPPED =
            Counter.builder()
                    .name("storm_login_queue_early_release_capped_total")
                    .help(
                            "Early releases skipped because the"
                                    + " -Dstorm.loginQueueMaxConcurrentLoaders ceiling was reached;"
                                    + " vanilla slot hold applied.")
                    .register(StormPrometheus.registry());

    private static final Gauge CONCURRENT_LOADERS =
            Gauge.builder()
                    .name("storm_login_queue_concurrent_loaders")
                    .help(
                            "Joiners currently loading client-side after their login-queue slot"
                                    + " was released early.")
                    .register(StormPrometheus.registry());

    private static final Histogram RECLAIMED_SECONDS =
            Histogram.builder()
                    .name("storm_login_queue_reclaimed_seconds")
                    .help(
                            "Per released join: seconds between freeing the slot and that client's"
                                    + " LoginQueueDone — queue serialization reclaimed from the"
                                    + " client-local load phase.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private LoginQueueEarlyReleaseMetrics() {}

    public static void released() {
        RELEASED.inc();
    }

    public static void capped() {
        CAPPED.inc();
    }

    public static void setConcurrentLoaders(int count) {
        CONCURRENT_LOADERS.set(count);
    }

    public static void observeReclaimedSeconds(double seconds) {
        RECLAIMED_SECONDS.observe(seconds);
    }
}
