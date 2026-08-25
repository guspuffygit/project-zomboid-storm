package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;

/**
 * Prometheus instruments for the {@code RequestDataManager} fixes (see {@code
 * io.pzstorm.storm.patch.fixes.RequestDataManagerFixPatch}).
 *
 * <ul>
 *   <li>{@code storm_requestdata_orphan_ack_total} — ACKs that matched no in-flight transfer (late,
 *       duplicate, or arriving after the entry was purged). Vanilla threw {@code
 *       IndexOutOfBoundsException} on this path and wedged the joiner's world download.
 *   <li>{@code storm_requestdata_stale_purged_total} — in-flight transfer entries reaped by the
 *       10-minute stale sweep. Each one is a leak the owning connection's disconnect never cleaned
 *       up; a nonzero rate means transfers are being orphaned somewhere new.
 * </ul>
 */
public final class RequestDataMetrics {

    private static final Counter ORPHAN_ACKS =
            Counter.builder()
                    .name("storm_requestdata_orphan_ack_total")
                    .help(
                            "RequestData ACKs with no matching in-flight transfer, dropped"
                                    + " gracefully (vanilla threw IndexOutOfBoundsException).")
                    .register(StormPrometheus.registry());

    private static final Counter STALE_PURGED =
            Counter.builder()
                    .name("storm_requestdata_stale_purged_total")
                    .help(
                            "In-flight RequestData transfers reaped by the 10-minute stale sweep"
                                    + " in RequestDataManager.disconnect.")
                    .register(StormPrometheus.registry());

    private RequestDataMetrics() {}

    public static void orphanAck() {
        ORPHAN_ACKS.inc();
    }

    public static void stalePurged() {
        STALE_PURGED.inc();
    }
}
