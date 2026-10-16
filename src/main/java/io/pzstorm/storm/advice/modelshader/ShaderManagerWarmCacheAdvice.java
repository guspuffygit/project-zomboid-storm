package io.pzstorm.storm.advice.modelshader;

import io.pzstorm.storm.shader.StormModelShaderCache;
import net.bytebuddy.asm.Advice;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;

/** A thrown native lookup/compile never records an entry; advice failures preserve its return. */
public final class ShaderManagerWarmCacheAdvice {
    private ShaderManagerWarmCacheAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.This ShaderManager manager,
            @Advice.Argument(0) String name,
            @Advice.Argument(1) boolean isStatic,
            @Advice.Argument(2) boolean instanced,
            @Advice.Return Shader shader) {
        StormModelShaderCache.record(manager, name, isStatic, instanced, shader);
    }
}
