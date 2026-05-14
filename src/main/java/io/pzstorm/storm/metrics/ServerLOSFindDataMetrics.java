package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.pzstorm.storm.cache.ServerLOSPlayerDataCache;

public final class ServerLOSFindDataMetrics {

    private static final Counter MISSES =
            Counter.builder()
                    .name("storm_server_los_find_data_misses_total")
                    .help(
                            "ServerLOS find-data cache misses (lookups that had to populate the cache).")
                    .register(StormPrometheus.registry());

    private static final GaugeWithCallback CACHE_SIZE =
            GaugeWithCallback.builder()
                    .name("storm_server_los_find_data_cache_size")
                    .help("Current entry count in the ServerLOS find-data cache.")
                    .callback(cb -> cb.call(ServerLOSPlayerDataCache.size()))
                    .register(StormPrometheus.registry());

    private ServerLOSFindDataMetrics() {}

    public static void recordMiss() {
        MISSES.inc();
    }
}
