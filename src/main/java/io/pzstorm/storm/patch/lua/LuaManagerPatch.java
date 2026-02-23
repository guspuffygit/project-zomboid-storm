package io.pzstorm.storm.patch.lua;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.Event;

/**
 * Patches {@link zombie.Lua.LuaEventManager} to trigger Java events when Lua events are triggered
 */
public class LuaManagerPatch extends StormClassTransformer {

    public LuaManagerPatch() {
        super("zombie.Lua.LuaEventManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(
                                        typePool.describe(
                                                        "io.pzstorm.storm.advice.TriggerEventAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("triggerEvent")
                                                .and(
                                                        ElementMatchers.takesArgument(
                                                                0, String.class))))
                .visit(
                        MemberSubstitution.strict()
                                .method(ElementMatchers.named("get"))
                                .replaceWith(
                                        typePool.describe(
                                                        "io.pzstorm.storm.patch.lua.LuaManagerPatch$DebugHook")
                                                .resolve()
                                                .getDeclaredMethods()
                                                .filter(ElementMatchers.named("safeGet"))
                                                .getOnly())
                                .on(ElementMatchers.named("reroute")));
    }

    public static class DebugHook {
        public static Object safeGet(ArrayList<?> list, int index) {
            Object item = list.get(index);

            if (item instanceof Event e) {
                LOGGER.trace("Reroute scanning Event: {}", e.name);
            }

            if (item instanceof LuaClosure c) {
                if (c.prototype == null) {
                    LOGGER.error("CRITICAL: LuaClosure prototype is NULL, {}, {}", c.debugName, c);
                } else if (c.prototype.filename == null) {
                    LOGGER.error(
                            "CRITICAL: LuaClosure filename is NULL. Function Name: {}",
                            c.prototype.name);
                    c.prototype.filename = "%s.lua".formatted(c.prototype.name);
                }
            }
            return item;
        }
    }
}
