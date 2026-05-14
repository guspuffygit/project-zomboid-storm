package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class LuaMainloopMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_lua_mainloop_call_duration_seconds")
                    .help("Duration of LuaMainloop advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private LuaMainloopMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
