package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Relevancy gate for the {@code IsoBarricade.syncIsoObject} override — the same treatment as {@code
 * IsoObjectSyncGatePatch} on the base method, which cannot cover this class because the override
 * replaces the whole body (single loop, client-only receive, no error guards or hot-save flag). See
 * {@code StormSyncIsoObjectGate} for the shared loop and fail-soft story.
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}); the advice additionally
 * guards on {@code GameServer.server} at runtime. Always on; permanent revert to vanilla if the
 * gated path ever throws.
 */
public class IsoBarricadeSyncGatePatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.objects.IsoBarricade";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.syncisoobject.IsoBarricadeSyncGateAdvice";

    public IsoBarricadeSyncGatePatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(
                        ElementMatchers.named("syncIsoObject")
                                .and(ElementMatchers.takesArguments(4)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "IsoBarricadeSyncGatePatch: IsoBarricade no longer declares its 4-arg"
                            + " syncIsoObject override — the name-string hook would silently no-op"
                            + " and reintroduce the ungated broadcast-to-every-connection sync"
                            + " loop. Re-verify the patch against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("syncIsoObject")
                                        .and(ElementMatchers.takesArguments(4))));
    }
}
