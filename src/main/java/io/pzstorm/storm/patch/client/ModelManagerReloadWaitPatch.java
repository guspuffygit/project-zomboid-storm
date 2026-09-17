package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Makes the connect-time model reload wait for its animation meshes.
 *
 * <p>{@code Core.ResetLua} calls {@code ModelManager.initAnimationMeshes(true)}, which starts the
 * mesh loads and returns without the wait loop that the {@code false} (boot) pass runs. {@code
 * loadModAnimations()} follows immediately and skips every mesh that is not yet ready, and nothing
 * in vanilla retries: the affected characters and vehicles stay invisible for the whole session.
 * The advice hooks the method's exit and, on the reload pass, runs the same pump vanilla uses at
 * boot until every mesh is ready or failed (see {@code
 * io.pzstorm.storm.advice.client.modelmeshwait.ModelMeshReloadWait}).
 *
 * <p>Why a client bytecode patch: the race is between two private steps of {@code Core.ResetLua} on
 * the client main thread, before any Lua event fires and out of the server's sight. Fail-soft: the
 * wait is bounded and disables itself permanently on any error.
 */
public class ModelManagerReloadWaitPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.client.modelmeshwait.";

    public ModelManagerReloadWaitPatch() {
        super("zombie.core.skinnedmodel.ModelManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "ModelMeshReloadWaitAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("initAnimationMeshes")
                                        .and(ElementMatchers.takesArguments(boolean.class))));
    }
}
