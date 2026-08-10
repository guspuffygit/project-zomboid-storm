package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;

/**
 * Tallies for the {@code StormSyncIsoObjectGate} relevancy gate on the server-side {@code
 * syncIsoObject} broadcast loops, exposed via scrape-time callbacks.
 *
 * <p>The tallies are plain (non-atomic) {@code long}s, same discipline as {@link
 * FluidContainerUpdateMetrics}: all writers run on the server main thread ({@code syncIsoObject} is
 * only reached from engine update and inbound-packet processing, both main-thread), and the scrape
 * thread reads dirty, which is acceptable for monotonic counters.
 */
public final class SyncIsoObjectGateMetrics {

    public static final int TARGET_ISO_OBJECT = 0;
    public static final int TARGET_WORLD_INVENTORY = 1;
    public static final int TARGET_BARRICADE = 2;
    public static final int TARGET_LIGHT_SWITCH = 3;

    private static final String[] TARGET_NAMES = {
        "iso_object", "world_inventory", "barricade", "light_switch"
    };

    /** Main-thread writers only — see class doc before adding call sites off the main thread. */
    public static final long[] gatedCalls = new long[TARGET_NAMES.length];

    public static final long[] vanillaCalls = new long[TARGET_NAMES.length];
    public static final long[] sentPackets = new long[TARGET_NAMES.length];
    public static final long[] suppressedPackets = new long[TARGET_NAMES.length];

    @SuppressWarnings("unused")
    private static final CounterWithCallback CALLS =
            CounterWithCallback.builder()
                    .name("pz_sync_iso_object_calls_total")
                    .help(
                            "Server-side syncIsoObject invocations by executed path: gated = the"
                                    + " StormSyncIsoObjectGate replacement ran (per-connection"
                                    + " relevancy applied) and the vanilla body was skipped; vanilla"
                                    + " = fell through to the vanilla broadcast-to-everyone body"
                                    + " (failure latch tripped). Target names the patched"
                                    + " method: iso_object = the IsoObject base method (covers"
                                    + " every subclass without its own override, e.g. hutches and"
                                    + " generators), plus the three patched overrides.")
                    .labelNames("target", "path")
                    .callback(
                            callback -> {
                                for (int i = 0; i < TARGET_NAMES.length; i++) {
                                    callback.call((double) gatedCalls[i], TARGET_NAMES[i], "gated");
                                    callback.call(
                                            (double) vanillaCalls[i], TARGET_NAMES[i], "vanilla");
                                }
                            })
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback PACKETS =
            CounterWithCallback.builder()
                    .name("pz_sync_iso_object_packets_total")
                    .help(
                            "Per-connection SyncIsoObject sends evaluated by the gated path: sent ="
                                    + " the connection was fully connected and the object was inside"
                                    + " its relevant range; suppressed = a send vanilla would have"
                                    + " made that the relevancy gate dropped (the client outside"
                                    + " range would have discarded the packet). Sends vanilla itself"
                                    + " already gates (IsoLightSwitch's server-initiated branch) are"
                                    + " counted as sent only, never suppressed.")
                    .labelNames("target", "outcome")
                    .callback(
                            callback -> {
                                for (int i = 0; i < TARGET_NAMES.length; i++) {
                                    callback.call((double) sentPackets[i], TARGET_NAMES[i], "sent");
                                    callback.call(
                                            (double) suppressedPackets[i],
                                            TARGET_NAMES[i],
                                            "suppressed");
                                }
                            })
                    .register(StormPrometheus.registry());

    private SyncIsoObjectGateMetrics() {}

    /** One gated call completed; {@code sent}/{@code suppressed} are its per-call send tallies. */
    public static void recordGated(int target, long sent, long suppressed) {
        gatedCalls[target]++;
        sentPackets[target] += sent;
        suppressedPackets[target] += suppressed;
    }

    /** One call fell through to the vanilla body. */
    public static void recordVanilla(int target) {
        vanillaCalls[target]++;
    }
}
