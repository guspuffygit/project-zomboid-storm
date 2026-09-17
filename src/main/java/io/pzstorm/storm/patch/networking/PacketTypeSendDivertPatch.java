package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Both JVMs. Lets Storm capture selected outgoing packets at {@code PacketType.send} and carry them
 * over the game-port TCP channel instead of RakNet (login-queue grant/place messages and the Lua
 * checksum exchange). Vanilla still builds every packet; only the last step is swapped.
 *
 * <p>Why a bytecode patch (client side included): the packets are written by private vanilla
 * methods deep inside {@code LoginQueue} and {@code ChecksumPacket}, and no Lua event or existing
 * Storm surface sees them before RakNet does. Fail-soft: {@code StormPacketDivert.tryDivert}
 * returns {@code false} on any problem and the vanilla send runs; a broken patch degrades to
 * vanilla UDP, never a lost packet.
 */
public class PacketTypeSendDivertPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.packetdivert.PacketTypeSendDivertAdvice";

    public PacketTypeSendDivertPatch() {
        super("zombie.network.PacketTypes$PacketType");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(ElementMatchers.named("send").and(ElementMatchers.takesArguments(1))));
    }
}
