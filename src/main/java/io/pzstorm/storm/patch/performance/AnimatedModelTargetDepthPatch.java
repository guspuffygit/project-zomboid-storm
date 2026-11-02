package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.client.MannequinDepthWriteGuard;
import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-side companion of {@link IsoMannequinModelDataPatch}. {@code AnimatedModel.initRenderData}
 * writes {@code modelInstance.targetDepth = 0.5F} on the main thread for every instance it queues
 * for a frame, while the render thread writes the real depth into the same field in {@code
 * WorldModelCamera.Begin} and reads it back for the shader. Vanilla never overlaps the two because
 * each frame gets a fresh pooled instance; the mannequin gate keeps one instance across frames, and
 * the main-thread reset then races the render thread and drops the mannequin behind the floor for a
 * frame. This patch routes that one field write through {@code
 * StormMannequinModelData.setDefaultTargetDepth}, which skips it for gate-kept instances and
 * performs it for everything else. Every other model path is unchanged.
 *
 * <p>Fails soft: the class stays vanilla when weaving throws or when the woven {@code
 * initRenderData} does not hold exactly one redirected write and no remaining direct write. The
 * gate only starts skipping {@code setModelData} once {@link MannequinDepthWriteGuard} confirms the
 * weave, so a refused weave costs the perf win and nothing else.
 *
 * <p>PZ-update re-validation: {@code initRenderData(StateInfo, AnimatedModelInstanceRenderData,
 * ModelInstance)} still holding exactly one write to {@code ModelInstance.targetDepth}; {@code
 * WorldModelCamera.Begin} still calling {@code setTargetDepth} before every world draw; {@code
 * ModelInstance.getOwner()} still returning the owning {@code AnimatedModel} for the main instance
 * and its sub-instances.
 */
public class AnimatedModelTargetDepthPatch extends StormClassTransformer {

    static final String ANIMATED_MODEL = "zombie.core.skinnedmodel.advancedanimation.AnimatedModel";
    static final String MODEL_INSTANCE = "zombie.core.skinnedmodel.model.ModelInstance";
    static final String MODEL_INSTANCE_INTERNAL = MODEL_INSTANCE.replace('.', '/');
    static final String HELPER_INTERNAL = IsoMannequinModelDataPatch.HELPER_INTERNAL;
    static final String METHOD = "initRenderData";
    static final String DESCRIPTOR =
            "(Lzombie/core/skinnedmodel/advancedanimation/AnimatedModel$StateInfo;"
                    + "Lzombie/core/skinnedmodel/advancedanimation/AnimatedModel$AnimatedModelInstanceRenderData;"
                    + "Lzombie/core/skinnedmodel/model/ModelInstance;)V";

    public AnimatedModelTargetDepthPatch() {
        super(ANIMATED_MODEL);
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        byte[] woven;
        try {
            woven = super.transform(rawClass);
            WeaveCheck.require(
                    woven, METHOD, DESCRIPTOR, HELPER_INTERNAL, "setDefaultTargetDepth", 1);
            WeaveCheck.requireFieldWrites(
                    woven, METHOD, DESCRIPTOR, MODEL_INSTANCE_INTERNAL, "targetDepth", 0);
            WeaveCheck.require(woven, null, null, HELPER_INTERNAL, "setDefaultTargetDepth", 1);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error(
                    "AnimatedModelTargetDepthPatch: {}; leaving AnimatedModel vanilla and the"
                            + " mannequin gate off",
                    e.toString());
            return rawClass;
        }
        MannequinDepthWriteGuard.setWoven(true);
        return woven;
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {

        // Reflecting on the helper would resolve AnimatedModel from its signatures while
        // AnimatedModel is mid-load, which throws ClassCircularityError.

        MethodDescription setter =
                typePool.describe(IsoMannequinModelDataPatch.HELPER)
                        .resolve()
                        .getDeclaredMethods()
                        .filter(
                                ElementMatchers.named("setDefaultTargetDepth")
                                        .and(ElementMatchers.takesArguments(2)))
                        .getOnly();
        return builder.visit(
                MemberSubstitution.relaxed()
                        .field(
                                ElementMatchers.named("targetDepth")
                                        .and(
                                                ElementMatchers.isDeclaredBy(
                                                        ElementMatchers.named(MODEL_INSTANCE))))
                        .onWrite()
                        .replaceWith(setter)
                        .on(ElementMatchers.named(METHOD).and(ElementMatchers.takesArguments(3))));
    }
}
