package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;

/**
 * Metrics for the {@code ServerCell.RecalcAll2} crash guard. Main-thread writer only ({@code
 * RecalcAll2} runs on the server main loop from {@code Load2}).
 */
public final class ServerCellRecalcGuardMetrics {

    private static long failures;

    @SuppressWarnings("unused")
    private static final CounterWithCallback FAILURES =
            CounterWithCallback.builder()
                    .name("storm_server_cell_recalc_failures_total")
                    .help(
                            "Throwables swallowed by the ServerCell.RecalcAll2 crash guard. Each"
                                    + " one is a cell that finished loading partially recalculated"
                                    + " instead of permanently wedging ServerMap.preupdate and"
                                    + " freezing the world loop. Any increase deserves a look at"
                                    + " the server log for the swallowed stack trace.")
                    .callback(callback -> callback.call((double) failures))
                    .register(StormPrometheus.registry());

    private ServerCellRecalcGuardMetrics() {}

    public static void recordFailure() {
        failures++;
    }
}
