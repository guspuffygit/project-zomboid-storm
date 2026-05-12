package io.pzstorm.storm.patch.security;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces the {@code LuaEventManager.triggerEvent("OnClientCommand", ...)} call inside
 * {@code GameServer.receiveClientCommand} with the gated wrapper in {@code ClientCommandSecurity}.
 *
 * <p>{@code receiveClientCommand} contains exactly one 5-arg {@code triggerEvent} invocation, so
 * scoping the substitution by method name + arg count uniquely identifies the call site. The
 * substitute method delegates back to the original {@code LuaEventManager.triggerEvent} when its
 * security check passes; only that single call site is rewritten, so the {@code TriggerEventAdvice}
 * applied to {@code LuaEventManager.triggerEvent} itself still runs for the forwarded dispatch.
 */
public class GameServerReceiveClientCommandPatch extends StormClassTransformer {

    private static final String HELPER_TYPE = "io.pzstorm.storm.security.ClientCommandSecurity";
    private static final String HELPER_METHOD = "gatedTriggerOnClientCommand";

    public GameServerReceiveClientCommandPatch() {
        super("zombie.network.GameServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription helper = typePool.describe(HELPER_TYPE).resolve();
        MethodDescription replacement =
                helper.getDeclaredMethods().filter(ElementMatchers.named(HELPER_METHOD)).getOnly();
        TypeDescription luaEventManager = typePool.describe("zombie.Lua.LuaEventManager").resolve();
        return builder.visit(
                MemberSubstitution.relaxed()
                        .method(
                                ElementMatchers.<MethodDescription>named("triggerEvent")
                                        .and(ElementMatchers.isDeclaredBy(luaEventManager))
                                        .and(ElementMatchers.takesArguments(5)))
                        .replaceWith(replacement)
                        .on(ElementMatchers.named("receiveClientCommand")));
    }
}
