package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.advice.mannequin.StormMannequinModelData;
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
 * frame. This patch routes that one field write through {@link
 * StormMannequinModelData#setDefaultTargetDepth}, which skips it for gate-kept instances and
 * performs it for everything else. Every other model path is unchanged.
 *
 * <p>Fails soft: the class stays vanilla when the woven {@code initRenderData} does not hold
 * exactly one redirected write and no remaining direct write, and the gate only starts skipping
 * {@code setModelData} once this weave is confirmed, so a refused weave costs the perf win and
 * nothing else.
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
        byte[] woven = super.transform(rawClass);
        try {
            WeaveCheck.require(
                    woven, METHOD, DESCRIPTOR, HELPER_INTERNAL, "setDefaultTargetDepth", 1);
            WeaveCheck.requireFieldWrites(
                    woven, METHOD, DESCRIPTOR, MODEL_INSTANCE_INTERNAL, "targetDepth", 0);
            WeaveCheck.require(woven, null, null, HELPER_INTERNAL, "setDefaultTargetDepth", 1);
        } catch (IllegalStateException e) {
            LOGGER.error(
                    "AnimatedModelTargetDepthPatch: {}; leaving AnimatedModel vanilla and the"
                            + " mannequin gate off",
                    e.getMessage());
            return rawClass;
        }
        StormMannequinModelData.setDepthWriteGuarded(true);
        return woven;
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        // Through the pool, never reflection: the helper declares a method taking AnimatedModel,
        // the class being defined right now, and reflecting on it would re-enter this load.
        MethodDescription gate =
                typePool.describe(IsoMannequinModelDataPatch.HELPER)
                        .resolve()
                        .getDeclaredMethods()
                        .filter(ElementMatchers.named("setDefaultTargetDepth"))
                        .getOnly();
        return builder.visit(
                MemberSubstitution.relaxed()
                        .field(
                                ElementMatchers.named("targetDepth")
                                        .and(
                                                ElementMatchers.isDeclaredBy(
                                                        ElementMatchers.named(MODEL_INSTANCE))))
                        .onWrite()
                        .replaceWith(gate)
                        .on(ElementMatchers.named(METHOD).and(ElementMatchers.takesArguments(3))));
    }
}
