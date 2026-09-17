package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Diverts loading-phase world-chunk requests ({@code RequestZipList}) to the Storm
 * game-port TCP channel when a handshake with a Storm server succeeded, replacing the 1000-byte
 * {@code SentChunk} UDP fragment pipeline for the initial area download. Gameplay-phase streaming
 * (after {@code GameClient.playerConnectSent}) stays on vanilla UDP.
 *
 * <p>Why a client bytecode patch: the request send lives inside {@code WorldStreamer.updateMain} on
 * the client main thread with no Lua involvement and no existing Storm surface. Fail-soft: the
 * advice helper declines (vanilla body runs, plain UDP) unless a healthy TCP session exists, and
 * any transport failure stops diversion for the session — in-flight requests then recover through
 * vanilla's own 8-second re-request timeout over UDP.
 *
 * <p>Companion patch: {@link WorldStreamerChunkTcpPatch} (dispatch ordering + receive locking).
 */
public class ChunkRequestOverTcpPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.client.chunksovertcp.";

    public ChunkRequestOverTcpPatch() {
        super("zombie.network.packets.RequestZipListPacket");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "RequestZipListWriteAdvice").resolve(), locator)
                        .on(ElementMatchers.named("write").and(ElementMatchers.takesArguments(1))));
    }
}
