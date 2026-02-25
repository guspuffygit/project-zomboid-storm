package io.pzstorm.storm.patch.lua;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.lua.LuaTypeStubGenerator;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Patches {@link zombie.Lua.LuaManager.Exposer#exposeAll()} to dump all exposed Java classes as
 * LuaLS-compatible type stubs after the method completes.
 *
 * <p>The generated stubs land in {@code lua_stubs/} relative to the server working directory and
 * can be pointed at by a {@code .luarc.json} workspace config for linting.
 */
public class LuaExposerDumpPatch extends StormClassTransformer {

    public LuaExposerDumpPatch() {
        super("zombie.Lua.LuaManager$Exposer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(ExposeAllAdvice.class)
                        .on(
                                ElementMatchers.named("exposeAll")
                                        .and(ElementMatchers.takesNoArguments())));
    }

    public static class ExposeAllAdvice {
        @Advice.OnMethodExit
        public static void afterExposeAll() {
            LuaTypeStubGenerator.generate();
        }
    }
}
