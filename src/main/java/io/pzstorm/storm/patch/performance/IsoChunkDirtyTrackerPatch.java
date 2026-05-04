package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

public class IsoChunkDirtyTrackerPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.chunkdirty.";

    public IsoChunkDirtyTrackerPatch() {
        super("zombie.iso.IsoChunk");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "IsoChunkMarkDirtyAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("flagForHotSave")
                                        .or(ElementMatchers.named("addBloodSplat"))
                                        .or(ElementMatchers.named("setBlendingDoneFull"))
                                        .or(ElementMatchers.named("setBlendingDonePartial"))
                                        .or(ElementMatchers.named("setBlendingModified"))
                                        .or(ElementMatchers.named("setModifDepth"))
                                        .or(ElementMatchers.named("setAttachmentsDoneFull"))
                                        .or(ElementMatchers.named("setAttachmentsState"))
                                        .or(ElementMatchers.named("setAttachmentsPartial"))
                                        .or(ElementMatchers.named("addGeneratorPos"))
                                        .or(ElementMatchers.named("addSpawnedRoom"))));
    }
}
