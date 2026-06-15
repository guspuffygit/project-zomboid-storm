package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class ClientServerMapCharacterInMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_client_server_map_character_in_call_duration_seconds")
                    .help("Duration of ClientServerMap.characterIn advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private ClientServerMapCharacterInMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
