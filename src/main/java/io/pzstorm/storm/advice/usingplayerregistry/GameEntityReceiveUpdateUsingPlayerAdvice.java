package io.pzstorm.storm.advice.usingplayerregistry;

import io.pzstorm.storm.entity.UsingPlayerRegistry;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Registry maintenance on {@code GameEntity.receiveUpdateUsingPlayer(ByteBufferReader,
 * IConnection)}: the server branch of that packet handler assigns the {@code usingPlayer} field
 * directly, bypassing {@code setUsingPlayer} — and it is the <em>primary</em> way {@code
 * usingPlayer} becomes non-null on a dedicated server (client crafting/entity UIs call the setter
 * on the client JVM; the server only sees the resulting entity packet). Exit advice re-syncs
 * registry membership from the entity's post-call {@code getUsingPlayer()} state, covering both the
 * assign and clear branches.
 *
 * <p>{@code onThrowable = Throwable.class} so a mid-parse exception still leaves the registry
 * consistent with whatever the field holds. Unconditional on the server — see {@link
 * GameEntitySetUsingPlayerAdvice}.
 */
public class GameEntityReceiveUpdateUsingPlayerAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.This Object entity) {
        if (!GameServer.server) {
            return;
        }
        UsingPlayerRegistry.syncFromEntity(entity);
    }
}
