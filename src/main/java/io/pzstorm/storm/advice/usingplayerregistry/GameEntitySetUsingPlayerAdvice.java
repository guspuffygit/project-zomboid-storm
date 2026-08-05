package io.pzstorm.storm.advice.usingplayerregistry;

import io.pzstorm.storm.entity.UsingPlayerRegistry;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Registry maintenance on {@code GameEntity.setUsingPlayer(IsoPlayer)}: a non-null argument
 * registers the entity in {@link UsingPlayerRegistry}, a null argument removes it. Unconditional on
 * the server — not gated by the {@code Storm.UsingPlayerSweepFastPath} sandbox option or the sweep
 * failure latch — so the registry is complete from boot and the option can be flipped live.
 *
 * <p>Parameters are typed {@code Object} so the inlined advice never references game types beyond
 * the transform target itself; {@link UsingPlayerRegistry} casts internally.
 */
public class GameEntitySetUsingPlayerAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This Object entity, @Advice.Argument(0) Object player) {
        if (!GameServer.server) {
            return;
        }
        UsingPlayerRegistry.onSetUsingPlayer(entity, player);
    }
}
