package io.pzstorm.storm.advice.packetscachelimitbypass;

import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class PacketsCacheLimitBypassAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return GameServer.server;
    }
}
