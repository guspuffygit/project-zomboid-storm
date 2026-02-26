package io.pzstorm.storm.patch.lua;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnLuaManagerInitEvent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.ZomboidFileSystem;

/** Patches {@link zombie.Lua.LuaManager} to log when Lua files are loaded via RunLuaInternal. */
public class LuaManagerPatch extends StormClassTransformer {

    public LuaManagerPatch() {
        super("zombie.Lua.LuaManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(RunLuaInternalAdvice.class)
                                .on(
                                        ElementMatchers.named("RunLuaInternal")
                                                .and(
                                                        ElementMatchers.takesArgument(
                                                                0, String.class))))
                .visit(Advice.to(InitAdvice.class).on(ElementMatchers.named("init")));
    }

    public static class RunLuaInternalAdvice {
        @Advice.OnMethodEnter
        public static void onRunLuaInternal(@Advice.Argument(0) String filename) {
            if (filename != null) {
                String absolutePath = ZomboidFileSystem.instance.resolveFileOrGUID(filename);

                LOGGER.debug("[RunLuaInternal] Loading file: {}", absolutePath);
            }
        }
    }

    public static class InitAdvice {
        @Advice.OnMethodExit
        public static void afterInit() {
            LOGGER.debug("LuaManager.init()");
            StormEventDispatcher.dispatchEvent(new OnLuaManagerInitEvent());
        }
    }
}
