package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.event.core.PacketEventDispatcher;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.raknet.UdpConnection;

/**
 * Generic patch applied to any packet class that overrides {@code processServer}. Dispatches a
 * {@link io.pzstorm.storm.event.zomboid.OnPacketReceivedEvent} through {@link
 * PacketEventDispatcher} after the method completes.
 *
 * <p>A single instance of this class is created per packet class name, but they all share the same
 * {@link ProcessServerAdvice} advice class.
 */
public class PacketReceivedPatch extends StormClassTransformer {

    public PacketReceivedPatch(String packetClassName) {
        super(packetClassName);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(ProcessServerAdvice.class).on(ElementMatchers.named("processServer")));
    }

    public static class ProcessServerAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object beforeProcessServer(
                @Advice.This Object self, @Advice.Argument(1) UdpConnection connection) {
            return PacketEventDispatcher.createTypedEvent(self, connection);
        }

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void afterProcessServer(
                @Advice.This Object self,
                @Advice.Argument(1) UdpConnection connection,
                @Advice.Enter Object typedEvent) {
            PacketEventDispatcher.dispatchPacket(self, connection, typedEvent);
        }
    }
}
