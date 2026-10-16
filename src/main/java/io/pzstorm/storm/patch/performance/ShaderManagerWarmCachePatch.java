package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.logging.StormLogger;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Companion to ModelCreateShaderCachePatch. Records only normally returned native singleton results
 * whose actual identity matches the requested tuple. Client-only and experimental.
 */
public class ShaderManagerWarmCachePatch extends StormClassTransformer {
    private static final String TARGET = "zombie.core.skinnedmodel.shader.ShaderManager";
    private static final String SHADER = "zombie.core.skinnedmodel.shader.Shader";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.modelshader.ShaderManagerWarmCacheAdvice";

    public ShaderManagerWarmCachePatch() {
        super(TARGET);
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        try {
            return super.transform(rawClass);
        } catch (Throwable failure) {
            StormLogger.LOGGER.warn(
                    "Shader cache recording unavailable; keeping native lookup", failure);
            return rawClass;
        }
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        TypeDescription shader = typePool.describe(SHADER).resolve();
        ElementMatcher.Junction<MethodDescription> method =
                ElementMatchers.named("getOrCreateShader")
                        .and(
                                ElementMatchers.takesArguments(
                                        String.class, boolean.class, boolean.class))
                        .and(ElementMatchers.returns(shader))
                        .and(ElementMatchers.not(ElementMatchers.isStatic()));
        if (target.getDeclaredMethods().filter(method).size() != 1
                || target.getDeclaredFields()
                                .filter(
                                        ElementMatchers.named("instance")
                                                .and(ElementMatchers.fieldType(target))
                                                .and(ElementMatchers.isStatic())
                                                .and(ElementMatchers.isFinal()))
                                .size()
                        != 1
                || target.getDeclaredFields()
                                .filter(
                                        ElementMatchers.named("shaders")
                                                .and(
                                                        ElementMatchers.fieldType(
                                                                java.util.ArrayList.class))
                                                .and(
                                                        ElementMatchers.not(
                                                                ElementMatchers.isStatic()))
                                                .and(ElementMatchers.isFinal()))
                                .size()
                        != 1
                || !shader.isFinal()
                || shader.getDeclaredFields()
                                .filter(
                                        ElementMatchers.named("name")
                                                .and(ElementMatchers.fieldType(String.class))
                                                .and(ElementMatchers.isFinal()))
                                .size()
                        != 1
                || shader.getDeclaredFields()
                                .filter(
                                        ElementMatchers.named("isStatic")
                                                .and(ElementMatchers.fieldType(boolean.class))
                                                .and(ElementMatchers.isFinal()))
                                .size()
                        != 1
                || shader.getDeclaredFields()
                                .filter(
                                        ElementMatchers.named("instanced")
                                                .and(ElementMatchers.fieldType(boolean.class))
                                                .and(ElementMatchers.isFinal()))
                                .size()
                        != 1) {
            throw new IllegalStateException(
                    "Shader manager/identity changed; revalidate native lifecycle");
        }
        return builder.visit(Advice.to(typePool.describe(ADVICE).resolve(), locator).on(method));
    }
}
