package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Instruments the chunk-download worker thread: batch service time, chunk classification, bytes on
 * the wire, and the empty-handed replies.
 *
 * <p>Pure measurement — no vanilla behaviour changes. It exists because the supply side of chunk
 * streaming was entirely unmeasured. {@code pz_player_download_server_update_call_duration_seconds}
 * times the main-thread dispatch call, which does nothing at all whenever the worker is busy, so a
 * peer whose worker is saturated reads as *cheap* on the only metric that existed. Everything that
 * actually costs time — compression, the blocking {@code IsoChunk.SafeRead}, the RakNet writes — is
 * inside this class and was invisible.
 *
 * <p>Three hooks on {@code PlayerDownloadServer$WorkerThread}:
 *
 * <ul>
 *   <li>{@code sendArray} — the span during which {@code ready} is false and the peer's dispatch
 *       slot is blocked, plus a hot/cold split of the batch that says whether the main thread had
 *       the chunks or the worker has to go find them.
 *   <li>{@code compressChunk} — the one place both the uncompressed and compressed sizes are
 *       available, and a 1:1 proxy for chunks actually sent.
 *   <li>{@code sendNotRequired} — requests answered with no payload, split by whether that was the
 *       CRC optimisation or an abandonment.
 * </ul>
 *
 * <p>All three run off the main thread. They only read the request object they were handed and
 * increment Prometheus metrics, which are thread-safe; no game lock is taken and no packet is sent,
 * so this does not violate the off-main-thread network rule.
 *
 * <p>Registration is gated on {@code StormEnv.isStormServer()} — a co-op host's client JVM can load
 * {@code PlayerDownloadServer} through {@code zombie.spnetwork}.
 */
public class ChunkStreamWorkerMetricsPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.chunkstream.";

    public ChunkStreamWorkerMetricsPatch() {
        super("zombie.network.PlayerDownloadServer$WorkerThread");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(typePool.describe(PKG + "SendArrayAdvice").resolve(), locator)
                                .on(
                                        ElementMatchers.named("sendArray")
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(typePool.describe(PKG + "CompressChunkAdvice").resolve(), locator)
                                .on(
                                        ElementMatchers.named("compressChunk")
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "SendNotRequiredAdvice").resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("sendNotRequired")
                                                .and(ElementMatchers.takesArguments(2))));
    }
}
