package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces {@code GameServer.receivePlayerStartPMChat} — the packet handler that vanilla routes to
 * {@code ChatServer.processPlayerStartWhisperChatPacket} — with a version that resolves the whisper
 * author from the delivering {@code UdpConnection} instead of trusting the client-supplied name,
 * and looks up the destination case-insensitively. See {@link
 * io.pzstorm.storm.advice.whisperchatfix.GameServerStartPMChatAdvice} for the failure modes this
 * fixes (stale {@code "Bob"} nickname, case-sensitive lookups, as-typed names breaking the dest
 * client's {@code WhisperChat.init()}).
 *
 * <p>Patched at the {@code GameServer} entry point rather than on {@code ChatServer} because only
 * the entry point receives the {@code UdpConnection} that authoritatively identifies the author.
 */
public class GameServerStartPMChatPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.whisperchatfix.";

    public GameServerStartPMChatPatch() {
        super("zombie.network.GameServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "GameServerStartPMChatAdvice").resolve(), locator)
                        .on(ElementMatchers.named("receivePlayerStartPMChat")));
    }
}
