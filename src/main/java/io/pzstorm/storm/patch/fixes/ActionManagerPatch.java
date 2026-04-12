package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Comprehensive debug logging patch for {@code ActionManager}. Instruments all key methods on both
 * server and client sides to track the full lifecycle of NetTimedActions. All log messages are
 * filtered by Steam ID via {@link NtaDebugLog}.
 *
 * <p>All advice classes are standalone files in {@code io.pzstorm.storm.advice.actionmanager},
 * referenced via {@code typePool.describe().resolve()} and {@code locator}.
 */
public class ActionManagerPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.actionmanager.";

    public ActionManagerPatch() {
        super("zombie.core.ActionManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(typePool.describe(PKG + "StartAdvice").resolve(), locator)
                                .on(
                                        ElementMatchers.named("start")
                                                .and(ElementMatchers.isStatic())
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(typePool.describe(PKG + "AddAdvice").resolve(), locator)
                                .on(
                                        ElementMatchers.named("add")
                                                .and(ElementMatchers.isStatic())
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(typePool.describe(PKG + "StopAdvice").resolve(), locator)
                                .on(
                                        ElementMatchers.named("stop")
                                                .and(ElementMatchers.isStatic())
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "StopPlayerActionsAdvice")
                                                .resolve(),
                                        locator)
                                .on(ElementMatchers.named("stopPlayerActions")))
                .visit(
                        Advice.to(typePool.describe(PKG + "RemoveAdvice").resolve(), locator)
                                .on(
                                        ElementMatchers.named("remove")
                                                .and(ElementMatchers.isStatic())
                                                .and(ElementMatchers.takesArguments(2))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "SetStateFromPacketAdvice")
                                                .resolve(),
                                        locator)
                                .on(ElementMatchers.named("setStateFromPacket")))
                .visit(
                        Advice.to(typePool.describe(PKG + "IsDoneAdvice").resolve(), locator)
                                .on(ElementMatchers.named("isDone")))
                .visit(
                        Advice.to(typePool.describe(PKG + "IsRejectedAdvice").resolve(), locator)
                                .on(ElementMatchers.named("isRejected")))
                .visit(
                        Advice.to(typePool.describe(PKG + "UpdateAdvice").resolve(), locator)
                                .on(
                                        ElementMatchers.named("update")
                                                .and(ElementMatchers.isStatic())
                                                .and(ElementMatchers.takesNoArguments())))
                .visit(
                        Advice.to(typePool.describe(PKG + "CreateNtaAdvice").resolve(), locator)
                                .on(ElementMatchers.named("createNetTimedAction")));
    }
}
