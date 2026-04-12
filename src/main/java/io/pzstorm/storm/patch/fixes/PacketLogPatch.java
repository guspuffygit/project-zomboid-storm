package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Logs ALL network packets flowing in and out, filtered by Steam ID. Patches the three chokepoint
 * methods on {@code PacketTypes.PacketType}:
 *
 * <ul>
 *   <li>{@code onServerPacket} — every packet the server receives (from our client)
 *   <li>{@code onClientPacket} — every packet the client receives (from the server)
 *   <li>{@code send} — every packet sent (either direction)
 * </ul>
 *
 * <p>Log lines use the same {@code [STORM-NTA]} prefix as other NTA debug patches and include
 * {@code PACKET-RECV} or {@code PACKET-SEND} tags for easy filtering.
 *
 * <p>Advice classes are standalone files referenced via {@code typePool.describe().resolve()} and
 * {@code locator}.
 */
public class PacketLogPatch extends StormClassTransformer {

    public PacketLogPatch() {
        super("zombie.network.PacketTypes$PacketType");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        String pkg = "io.pzstorm.storm.advice.packet.";
        return builder.visit(
                        Advice.to(typePool.describe(pkg + "OnServerAdvice").resolve(), locator)
                                .on(ElementMatchers.named("onServerPacket")))
                .visit(
                        Advice.to(typePool.describe(pkg + "OnClientAdvice").resolve(), locator)
                                .on(
                                        ElementMatchers.named("onClientPacket")
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(typePool.describe(pkg + "SendAdvice").resolve(), locator)
                                .on(ElementMatchers.named("send")));
    }
}
