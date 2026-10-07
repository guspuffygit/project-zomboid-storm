package io.pzstorm.storm.advice.modelshader;

import io.pzstorm.storm.shader.StormModelShaderCache;
import net.bytebuddy.asm.Advice;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.shader.Shader;

/** Reuses only a shader already returned by the native singleton, without invoking OpenGL. */
public final class ModelCreateShaderCacheAdvice {
    private ModelCreateShaderCacheAdvice() {}

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(
            @Advice.Argument(0) String name,
            @Advice.FieldValue("isStatic") boolean isStatic,
            @Advice.FieldValue(value = "effect", readOnly = false) Shader effect) {
        if (ModelManager.noOpenGL) return false;
        Shader cached = StormModelShaderCache.find(name, isStatic, false);
        if (cached == null) return false;
        effect = cached;
        return true;
    }
}
