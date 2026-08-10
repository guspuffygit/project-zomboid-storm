package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Relevancy gate for {@code IsoLightSwitch.syncIsoObject(boolean, byte, UdpConnection)} — the
 * operative 3-arg method; the inherited 4-arg signature just delegates to it, so patching the 3-arg
 * covers both entry points. Vanilla already {@code isRelevantTo}-gates the server-initiated branch
 * but leaves the client-relay branch broadcasting to every other connection; light switches flip
 * often on a populated server (every powered lamp interaction syncs). See {@code
 * StormSyncIsoObjectGate} for the shared loop and fail-soft story.
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}); the advice additionally
 * guards on {@code GameServer.server} at runtime. Always on; permanent revert to vanilla if the
 * gated path ever throws.
 */
public class IsoLightSwitchSyncGatePatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.objects.IsoLightSwitch";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.syncisoobject.IsoLightSwitchSyncGateAdvice";

    public IsoLightSwitchSyncGatePatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(
                        ElementMatchers.named("syncIsoObject")
                                .and(ElementMatchers.takesArguments(3)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "IsoLightSwitchSyncGatePatch: IsoLightSwitch no longer declares the 3-arg"
                            + " syncIsoObject — the name-string hook would silently no-op and"
                            + " reintroduce the ungated relay broadcast in the sync loop."
                            + " Re-verify the patch against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("syncIsoObject")
                                        .and(ElementMatchers.takesArguments(3))));
    }
}
