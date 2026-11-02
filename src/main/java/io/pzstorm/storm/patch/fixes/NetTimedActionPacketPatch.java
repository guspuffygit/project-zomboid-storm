package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Diagnostics on {@code NetTimedActionPacket}. On the server it names timed actions that fail to
 * rebuild from the wire (see {@link NetTimedActionPacketFix}); on both sides it writes {@link
 * NtaDebugLog} traces for the players named in {@code -Dstorm.nta.debugPlayers}. Neither advice
 * changes the vanilla outcome.
 *
 * <p><b>This class must not reference any game type.</b> It is linked while the {@code
 * StormClassTransformers} static registration block runs — before {@code
 * StormBootstrap.hasLoaded()} is true — so any game class the bytecode verifier pulls in here gets
 * defined untransformed, silently disabling every transformer registered for it. Game-type logic
 * belongs in {@link NetTimedActionPacketFix}, which is only loaded when the woven advice first
 * executes.
 *
 * <p><b>Registration order matters:</b> this patch must be registered <em>before</em> {@link
 * io.pzstorm.storm.patch.networking.PacketReceivedPatch} so that the generic packet event
 * dispatching wraps around it.
 */
public class NetTimedActionPacketPatch extends StormClassTransformer {

    public NetTimedActionPacketPatch() {
        super("zombie.network.packets.NetTimedActionPacket");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        String pkg = "io.pzstorm.storm.advice.nta.";
        return builder.visit(
                        Advice.to(typePool.describe(pkg + "ProcessServerAdvice").resolve(), locator)
                                .on(ElementMatchers.named("processServer")))
                .visit(
                        Advice.to(typePool.describe(pkg + "ProcessClientAdvice").resolve(), locator)
                                .on(ElementMatchers.named("processClient")));
    }
}
