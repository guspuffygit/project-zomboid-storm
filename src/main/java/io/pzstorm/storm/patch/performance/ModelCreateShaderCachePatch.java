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
 * Client-only, opt-in via storm.experimental.clientperf. Repeated CreateShader calls in native
 * model loading queue render-context work even when the append-only shader manager has the exact
 * variant. A warmed secondary index can assign that same Shader object directly. Cold lookups,
 * exceptions and noOpenGL behavior retain the native body; no shader compilation is moved off the
 * render context. Java instrumentation is necessary because these calls originate in Java asset
 * loading. Revalidate CreateShader, manager lifecycle and Shader identity on game updates.
 */
public class ModelCreateShaderCachePatch extends StormClassTransformer {
    private static final String TARGET = "zombie.core.skinnedmodel.model.Model";
    private static final String SHADER = "zombie.core.skinnedmodel.shader.Shader";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.modelshader.ModelCreateShaderCacheAdvice";

    public ModelCreateShaderCachePatch() {
        super(TARGET);
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        try {
            return super.transform(rawClass);
        } catch (Throwable failure) {
            StormLogger.LOGGER.warn(
                    "Model shader cache unavailable; keeping native lookup", failure);
            return rawClass;
        }
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        ElementMatcher.Junction<MethodDescription> method =
                ElementMatchers.named("CreateShader")
                        .and(ElementMatchers.takesArguments(String.class))
                        .and(ElementMatchers.returns(void.class))
                        .and(ElementMatchers.not(ElementMatchers.isStatic()));
        if (target.getDeclaredMethods().filter(method).size() != 1
                || target.getDeclaredFields()
                                .filter(
                                        ElementMatchers.named("effect")
                                                .and(
                                                        ElementMatchers.fieldType(
                                                                typePool.describe(SHADER)
                                                                        .resolve()))
                                                .and(
                                                        ElementMatchers.not(
                                                                ElementMatchers.isStatic())))
                                .size()
                        != 1
                || target.getDeclaredFields()
                                .filter(
                                        ElementMatchers.named("isStatic")
                                                .and(ElementMatchers.fieldType(boolean.class))
                                                .and(
                                                        ElementMatchers.not(
                                                                ElementMatchers.isStatic())))
                                .size()
                        != 1) {
            throw new IllegalStateException(
                    "Model shader method/fields changed; revalidate native source");
        }
        return builder.visit(Advice.to(typePool.describe(ADVICE).resolve(), locator).on(method));
    }
}
