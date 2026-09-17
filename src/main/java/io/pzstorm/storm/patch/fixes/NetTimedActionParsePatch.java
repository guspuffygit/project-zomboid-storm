package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Turns a failed {@code NetTimedAction.parse} into a rejected action instead of a dropped one.
 *
 * <p>The server rebuilds a client's timed action from the packet in two steps: it deserializes the
 * constructor arguments ({@code actionArgs.load}) and then calls the Lua constructor under {@code
 * pcall}. The constructor step is already safe in vanilla — Kahlua's pcall catches everything and
 * {@code parse} sets {@code action = null}, so {@code processServer} sends a Reject. The
 * deserialization step is not: {@code PZNetKahluaTableImpl.loadComponent} dereferences a null
 * {@code GameEntity} when the client names a craft bench the server has not loaded, and {@code
 * ContainerID.findObject} dereferences a null {@code containingItem}. Those escape {@code parse},
 * {@code processServer} never runs, and the client gets neither Accept nor Reject. The client's
 * action then sits at 100% forever, every queued action behind it is blocked, and the player also
 * collects an {@code AntiCheat.PacketException} strike for a vanilla bug.
 *
 * <p>The advice catches any {@code RuntimeException} thrown by {@code parse}, clears {@code
 * action}, and swallows the exception so the existing null-action Reject path in {@link
 * NetTimedActionPacketFix} fires. Runtime logic lives in {@link NetTimedActionParseFix}; this class
 * must not reference any game type (see {@link NetTimedActionPacketPatch}).
 *
 * <p>Server-only. If the advice itself throws, {@code suppress} lets the original exception
 * propagate exactly as vanilla would.
 */
public class NetTimedActionParsePatch extends StormClassTransformer {

    public NetTimedActionParsePatch() {
        super("zombie.core.NetTimedAction");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe("io.pzstorm.storm.advice.nta.ParseAdvice")
                                        .resolve(),
                                locator)
                        .on(ElementMatchers.named("parse").and(ElementMatchers.takesArguments(2))));
    }
}
