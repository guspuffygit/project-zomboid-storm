package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Measures chunk demand at the point it enters the server.
 *
 * <p>Needs its own patch because {@code RequestZipListPacket} is not in {@code
 * PacketEventDispatcher.SUPPORTED_PACKETS}, so the packet event bus never sees it — the only
 * chunk-related packet that is covered is {@code RequestLargeAreaZipPacket}, which fires once
 * during the initial map download and never during play.
 *
 * <p>Without this, the backlog gauges show a queue growing but not whether the cause is a demand
 * spike (grid scroll, teleport, an 8-second resend wave) or supply falling off. Pure measurement.
 */
public class RequestZipListParsePatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.chunkstream.RequestZipListParseAdvice";

    public RequestZipListParsePatch() {
        super("zombie.network.packets.RequestZipListPacket");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(ElementMatchers.named("parse").and(ElementMatchers.takesArguments(2))));
    }
}
