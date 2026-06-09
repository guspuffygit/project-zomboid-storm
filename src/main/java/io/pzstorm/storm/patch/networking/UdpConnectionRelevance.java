package io.pzstorm.storm.patch.networking;

import zombie.core.raknet.UdpConnection;

/**
 * Pure logic behind {@link UdpConnectionRelevancePatch}: returns whether a connection has completed
 * the world-download handshake and can meaningfully participate in relevance checks.
 *
 * <p>{@code connRef} is typed {@code Object} so the inlined advice site does not embed a checkcast
 * against {@code UdpConnection}. A typed parameter would let javac elide the cast and the JVM
 * verifier would resolve {@code UdpConnection} at patch registration — before the transformer is in
 * place to apply itself. See the {@code feedback_elided_cast_load} memory.
 */
public final class UdpConnectionRelevance {

    private UdpConnectionRelevance() {}

    public static boolean isConnectionReady(Object connRef) {
        UdpConnection conn = (UdpConnection) connRef;
        return conn.isFullyConnected();
    }
}
