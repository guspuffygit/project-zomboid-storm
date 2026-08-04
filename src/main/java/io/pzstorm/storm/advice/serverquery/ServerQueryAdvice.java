package io.pzstorm.storm.advice.serverquery;

import io.pzstorm.storm.query.StormQueryResponder;
import net.bytebuddy.asm.Advice;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;

/**
 * Intercepts Storm's launcher query packet at the top of {@code GameServer.addIncoming}.
 *
 * <p>This is the earliest point at which the packet id and the sending {@code UdpConnection} are
 * both in hand. Answering here rather than in {@code mainLoopDealWithNetData} is what lets the
 * query work pre-login: that later gate force-disconnects any connection with no username that
 * sends anything outside its five-packet allowlist. It also avoids vanilla's unknown-id path, which
 * leaks the pooled {@code ZomboidNetData} it allocated.
 *
 * <p>Vanilla's body is skipped only for our own packet ids; every other packet falls through
 * untouched.
 */
public class ServerQueryAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.Argument(0) short id,
            @Advice.Argument(1) ByteBufferReader bb,
            @Advice.Argument(2) UdpConnection connection) {
        if (!GameServer.server) {
            return false;
        }
        return StormQueryResponder.handle(id, bb, connection);
    }
}
