package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces the body of {@code IsoGridSquare.removeGlassAttachments(IsoWindow)} with {@link
 * GlassAttachmentRemovalGuard#removeGlassAttachments(Object, Object)}.
 *
 * <p>Vanilla steps its loop index back after every {@code RemoveTileObject} call without checking
 * that the object left the list. Multi-tile light switches whose second part cannot be resolved
 * survive the call, so the loop re-examines the same object forever and the main thread sits at 0
 * TPS with no exception and no log line. One smashed window is enough. Registered on both JVMs: the
 * client runs the same code on its own copy of the square when it receives the smash.
 */
public class IsoGridSquareRemoveGlassAttachmentsPatch extends StormClassTransformer {

    private static final String PKG =
            "io.pzstorm.storm.advice.isogridsquareremoveglassattachments.";

    public IsoGridSquareRemoveGlassAttachmentsPatch() {
        super("zombie.iso.IsoGridSquare");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "IsoGridSquareRemoveGlassAttachmentsAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("removeGlassAttachments")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
