package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes a vanilla bug in {@code NetTimedActionPacket.processServer()} where both the Accept and
 * Reject response paths serialize the packet ({@code this}) instead of the server-side action
 * ({@code act}).
 *
 * <p>Since the packet's state is always {@code Request} when entering the handler, the client
 * always receives {@code Request} state back regardless of whether the server accepted or rejected
 * the action. This means:
 *
 * <ul>
 *   <li>The client never transitions out of the Request state
 *   <li>The client never receives the server-calculated duration (Accept path)
 *   <li>The client cannot distinguish acceptance from rejection
 * </ul>
 *
 * <p>This patch replaces the method body and writes {@code act} (with the correct state and
 * duration) instead of {@code this}. The replacement logic lives in {@link
 * NetTimedActionPacketFix}.
 *
 * <p><b>This class must not reference any game type.</b> It is linked while the {@code
 * StormClassTransformers} static registration block runs — before {@code
 * StormBootstrap.hasLoaded()} is true — so any game class the bytecode verifier pulls in here (e.g.
 * via a method whose verification needs a game-class subtype check) gets defined untransformed,
 * silently disabling every transformer registered for it. That is exactly what happened when {@code
 * processServerFixed(NetTimedActionPacket, UdpConnection)} lived in this class: verifying {@code
 * act.copyFrom(packet)} loaded {@code NetTimedActionPacket} during registration and neither this
 * patch nor its {@code PacketReceivedPatch} ever applied. Game-type logic belongs in {@link
 * NetTimedActionPacketFix}, which is only loaded when the woven advice first executes.
 *
 * <p><b>Registration order matters:</b> this patch must be registered <em>before</em> {@link
 * io.pzstorm.storm.patch.networking.PacketReceivedPatch} so that the generic packet event
 * dispatching wraps around the fixed logic. The corrected code runs inside the enter advice; the
 * original body is skipped via {@code skipOn}. If the advice throws, {@code suppress} causes a
 * fallback to the original (buggy) method body.
 *
 * <p><b>Selective rollout:</b> set the system property {@code storm.fix.nettimedaction.players} to
 * restrict the fix to specific players. Accepts a comma-separated list of Steam IDs and/or
 * usernames (case-insensitive), or {@code *} for all players (the default when unset). Players not
 * in the list fall through to the original vanilla method. Example:
 *
 * <pre>-Dstorm.fix.nettimedaction.players=76561198000000000,bob</pre>
 *
 * <p><b>Advice classes</b> are standalone files referenced via {@code
 * typePool.describe().resolve()} and {@code locator}, following the same pattern as {@link
 * io.pzstorm.storm.advice.TriggerEventAdvice}.
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
