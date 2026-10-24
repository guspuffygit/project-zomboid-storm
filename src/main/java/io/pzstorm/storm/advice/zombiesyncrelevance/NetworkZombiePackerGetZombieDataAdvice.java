package io.pzstorm.storm.advice.zombiesyncrelevance;

import io.pzstorm.storm.zombie.StormZombieSyncRelevance;
import net.bytebuddy.asm.Advice;
import zombie.core.raknet.UdpConnection;

/**
 * Brackets {@code NetworkZombiePacker.getZombieData(UdpConnection, ZombieSynchronizationPacket)}
 * with the connection it packs for, which {@link StormZombieSyncRelevance#ownerIfRelevant} reads.
 * The vanilla body runs as written.
 */
public class NetworkZombiePackerGetZombieDataAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) UdpConnection connection) {
        StormZombieSyncRelevance.begin(connection);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        StormZombieSyncRelevance.end();
    }
}
