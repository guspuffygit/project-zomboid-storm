package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class CalcCountPlayersInRelevantPositionMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name(
                            "pz_udp_connection_calc_count_players_in_relevant_position_call_duration_seconds")
                    .help(
                            "Duration of UdpConnection.calcCountPlayersInRelevantPosition advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private CalcCountPlayersInRelevantPositionMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
