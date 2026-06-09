package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fix for a vanilla bug where zombies accumulate at world origin (0,0) on long-running MP servers.
 *
 * <p><b>Root cause:</b> {@code UdpConnection}'s constructor initializes {@code releventPos[0] = new
 * Vector3()} — the zero vector. Until the first {@code PlayerPacket} fires from the client
 * (typically after a 30+ second world-download handshake), {@code releventPos[0]} stays at {@code
 * (0,0,0)} and {@code relevantRange} stays at its default {@code 0}. {@code
 * ServerMap.outsidePlayerInfluence} iterates every connection and calls {@code
 * c.isRelevantTo(corner_x, corner_y)} for each ServerCell's four corners; with {@code relevantRange
 * == 0} and {@code releventPos == (0,0,0)}, the relevance check passes for any cell whose corner
 * touches world {@code (0,0)} — cells {@code (0,0)}, {@code (0,-1)}, {@code (-1,0)}, {@code
 * (-1,-1)}. Cell {@code (-1,-1)} is suppressed by an unrelated guard, but the other three stay
 * loaded for the duration of every client handshake. {@code LoadedAreas(serverCells=true)} then
 * reports them to the native pop manager, which materializes virtual zombies into the empty origin
 * terrain. These zombies persist after the handshake completes.
 *
 * <p><b>Fix:</b> short-circuit all four relevance methods on {@code UdpConnection} to return "not
 * relevant" whenever {@code isFullyConnected()} returns false:
 *
 * <ul>
 *   <li>{@code isRelevantTo(float, float)} → {@code false}
 *   <li>{@code RelevantToPlayerIndex(int, float, float)} → {@code false}
 *   <li>{@code RelevantTo(float, float, float)} → {@code false}
 *   <li>{@code getRelevantAndDistance(float, float, float)} → {@link Float#POSITIVE_INFINITY}
 * </ul>
 *
 * <p>This is the correct invariant: a connection still receiving its world download cannot
 * meaningfully act on packets routed via relevance, so it should not influence cell-keep-alive,
 * packet broadcast, or zombie-handoff decisions. Several vanilla call sites already gate on {@code
 * isFullyConnected()} themselves ({@code GameServer.java:1730, 2276, 2325, 3332}); this patch
 * promotes the gate into the chokepoint so every caller gets it.
 *
 * <p>Server-only — see registration in {@link io.pzstorm.storm.core.StormClassTransformers}.
 */
public class UdpConnectionRelevancePatch extends StormClassTransformer {

    private static final String BOOL_ADVICE =
            "io.pzstorm.storm.advice.udpconnection.IsRelevantToBoolAdvice";
    private static final String FLOAT_ADVICE =
            "io.pzstorm.storm.advice.udpconnection.GetRelevantAndDistanceAdvice";

    public UdpConnectionRelevancePatch() {
        super("zombie.core.raknet.UdpConnection");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(typePool.describe(BOOL_ADVICE).resolve(), locator)
                                .on(
                                        ElementMatchers.named("isRelevantTo")
                                                .and(
                                                        ElementMatchers.takesArguments(
                                                                float.class, float.class))
                                                .and(ElementMatchers.returns(boolean.class))))
                .visit(
                        Advice.to(typePool.describe(BOOL_ADVICE).resolve(), locator)
                                .on(
                                        ElementMatchers.named("RelevantToPlayerIndex")
                                                .and(
                                                        ElementMatchers.takesArguments(
                                                                int.class,
                                                                float.class,
                                                                float.class))
                                                .and(ElementMatchers.returns(boolean.class))))
                .visit(
                        Advice.to(typePool.describe(BOOL_ADVICE).resolve(), locator)
                                .on(
                                        ElementMatchers.named("RelevantTo")
                                                .and(
                                                        ElementMatchers.takesArguments(
                                                                float.class,
                                                                float.class,
                                                                float.class))
                                                .and(ElementMatchers.returns(boolean.class))))
                .visit(
                        Advice.to(typePool.describe(FLOAT_ADVICE).resolve(), locator)
                                .on(
                                        ElementMatchers.named("getRelevantAndDistance")
                                                .and(
                                                        ElementMatchers.takesArguments(
                                                                float.class,
                                                                float.class,
                                                                float.class))
                                                .and(ElementMatchers.returns(float.class))));
    }
}
