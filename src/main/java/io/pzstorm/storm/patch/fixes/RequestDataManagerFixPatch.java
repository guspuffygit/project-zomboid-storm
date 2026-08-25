package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes two defects in {@code zombie.network.RequestDataManager}, the server side of the join-time
 * world download ({@code PersistentOutfits}, {@code SharedDescriptors}, {@code RadioData}, {@code
 * WorldMap} — the "Downloading large file" phase of the loading screen):
 *
 * <ul>
 *   <li>{@code ACKWasReceived} iterates {@code i <= requests.size()} and throws {@code
 *       IndexOutOfBoundsException} whenever no entry matches the ACKing connection. {@code
 *       GameServer} swallows the exception per-packet, the ACK is lost, and the joining client
 *       waits forever on a transfer the server will never resume — {@code
 *       GameClient.GameLoadingRequestData()} has no timeout, so the player sits on the loading
 *       screen until the stalled-connection reaper kills them. See {@link
 *       io.pzstorm.storm.advice.requestdatafix.RequestDataAckAdvice}.
 *   <li>{@code disconnect} purges <b>any</b> in-flight transfer older than 60 seconds whenever
 *       <b>any</b> player disconnects, orphaning slow joiners' downloads and feeding the crash
 *       above — the main real-world trigger on a busy server. See {@link
 *       io.pzstorm.storm.advice.requestdatafix.RequestDataDisconnectAdvice}.
 * </ul>
 *
 * <p>Both advices fail soft: if reflection against the private {@code requests} list / {@code
 * RequestData} entry class breaks on a game update, the vanilla bodies run unchanged.
 *
 * <h2>Registration</h2>
 *
 * <p>Gated server-only in {@code StormClassTransformers}: both patched methods are only invoked in
 * the dedicated-server JVM ({@code RequestDataPacket.processServer} and {@code
 * GameServer.disconnect}); the client half of the class ({@code receiveClientData}) is untouched.
 */
public class RequestDataManagerFixPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.requestdatafix.";

    public RequestDataManagerFixPatch() {
        super("zombie.network.RequestDataManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "RequestDataAckAdvice").resolve(),
                                        locator)
                                .on(ElementMatchers.named("ACKWasReceived")))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "RequestDataDisconnectAdvice")
                                                .resolve(),
                                        locator)
                                .on(ElementMatchers.named("disconnect")));
    }
}
