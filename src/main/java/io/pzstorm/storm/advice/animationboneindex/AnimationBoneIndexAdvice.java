package io.pzstorm.storm.advice.animationboneindex;

import io.pzstorm.storm.animation.StormBoneIndexLookup;
import net.bytebuddy.asm.Advice;
import zombie.core.skinnedmodel.model.SkinningData;

/** Keeps missing bones and null-valued entries on the unchanged native method body. */
public final class AnimationBoneIndexAdvice {
    private AnimationBoneIndexAdvice() {}

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static Integer onEnter(
            @Advice.FieldValue("skinningData") SkinningData data,
            @Advice.Argument(0) String boneName) {
        return data == null ? null : StormBoneIndexLookup.existingIndex(data.boneIndices, boneName);
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter Integer index, @Advice.Return(readOnly = false) int result) {
        if (index != null) result = index;
    }
}
