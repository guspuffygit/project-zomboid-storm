package io.pzstorm.storm.advice.loginduplicateguard;

import io.pzstorm.storm.connection.StormTcpLogin;
import net.bytebuddy.asm.Advice;

/**
 * Entry hook on {@code LoginPacket.processServer(PacketType, UdpConnection)}. A {@code true} from
 * the guard skips the vanilla body for a connection that already logged in.
 */
public class LoginPacketProcessServerAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.Argument(1) Object connection) {
        return StormTcpLogin.isDuplicateLogin(connection);
    }
}
