package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;

/**
 * Tallies for the {@code StormGameEntityBroadcastGate} relevancy gate on the server-side {@code
 * GameEntityNetwork.sendPacketData} broadcast path, exposed via scrape-time callbacks.
 *
 * <p>The tallies are plain (non-atomic) {@code long}s, same discipline as {@link
 * SyncIsoObjectGateMetrics}: all writers run on the server main thread ({@code sendPacketData} is
 * only reached from engine update and inbound-packet processing, both main-thread), and the scrape
 * thread reads dirty, which is acceptable for monotonic counters.
 */
public final class GameEntityBroadcastGateMetrics {

    /** Main-thread writers only — see class doc before adding call sites off the main thread. */
    public static long gatedCalls;

    public static long bypassedCalls;
    public static long vanillaCalls;
    public static long sentPackets;
    public static long suppressedPackets;

    @SuppressWarnings("unused")
    private static final CounterWithCallback CALLS =
            CounterWithCallback.builder()
                    .name("pz_game_entity_broadcast_calls_total")
                    .help(
                            "Server-side GameEntityNetwork.sendPacketData broadcast invocations by"
                                    + " executed path: gated = the StormGameEntityBroadcastGate"
                                    + " replacement ran (per-connection relevancy applied) and the"
                                    + " vanilla sendToAll was skipped; bypassed = fell through to"
                                    + " vanilla by design (non-IsoObject entity, no square, or a"
                                    + " vanilla validation-warn case); vanilla = fell through"
                                    + " because the failure latch tripped. Targeted"
                                    + " (non-broadcast) sends are not counted.")
                    .labelNames("path")
                    .callback(
                            callback -> {
                                callback.call((double) gatedCalls, "gated");
                                callback.call((double) bypassedCalls, "bypassed");
                                callback.call((double) vanillaCalls, "vanilla");
                            })
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback PACKETS =
            CounterWithCallback.builder()
                    .name("pz_game_entity_broadcast_packets_total")
                    .help(
                            "Per-connection GameEntity sends evaluated by the gated path: sent ="
                                    + " the connection was fully connected and the entity's square"
                                    + " was inside its relevant range; suppressed = a send vanilla"
                                    + " would have made that the relevancy gate dropped (the"
                                    + " client outside range has no chunk holding the entity and"
                                    + " re-syncs from the chunk payload when it streams back in).")
                    .labelNames("outcome")
                    .callback(
                            callback -> {
                                callback.call((double) sentPackets, "sent");
                                callback.call((double) suppressedPackets, "suppressed");
                            })
                    .register(StormPrometheus.registry());

    private GameEntityBroadcastGateMetrics() {}

    /** One gated call completed; {@code sent}/{@code suppressed} are its per-call send tallies. */
    public static void recordGated(long sent, long suppressed) {
        gatedCalls++;
        sentPackets += sent;
        suppressedPackets += suppressed;
    }

    /** One broadcast call stayed vanilla by design (not a latch failure). */
    public static void recordBypassed() {
        bypassedCalls++;
    }

    /** One broadcast call fell through to the vanilla body because the failure latch tripped. */
    public static void recordVanilla() {
        vanillaCalls++;
    }
}
