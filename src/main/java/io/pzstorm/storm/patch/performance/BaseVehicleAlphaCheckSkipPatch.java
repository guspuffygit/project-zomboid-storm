package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Skips {@code BaseVehicle.couldSeeIntersectedSquare(int)} on the dedicated server, where its only
 * consumer ({@code IsoObject.setTargetAlpha}) is a no-op — see {@link
 * io.pzstorm.storm.vehicles.StormVehicleAlphaCheckSkip}. Profiled at ~4.7% of the main thread on a
 * 1,100-vehicle server. Server-only by registration gate; the advice additionally checks {@code
 * GameServer.server} at call time.
 *
 * <p>Re-validate on every game update: the skip is exact only while {@code
 * IsoObject.setTargetAlpha(int, float)} stays gated on {@code !GameServer.server} and {@code
 * BaseVehicle.update()} consumes the result for nothing but that call.
 *
 * <p>Kill switch: the {@code Storm.VehicleAlphaCheckSkip} sandbox option ({@code false} restores
 * the vanilla computation; live-appliable via admin sandbox push).
 */
public class BaseVehicleAlphaCheckSkipPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.vehicles.BaseVehicle";
    private static final String METHOD = "couldSeeIntersectedSquare";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.vehiclealphacheck.BaseVehicleCouldSeeIntersectedSquareAdvice";

    public BaseVehicleAlphaCheckSkipPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods().filter(ElementMatchers.named(METHOD)).isEmpty()) {
            throw new IllegalStateException(
                    "BaseVehicleAlphaCheckSkipPatch: BaseVehicle no longer declares "
                            + METHOD
                            + " — the name-string hook would silently no-op and reintroduce the"
                            + " per-vehicle square walk. Re-verify the patch against the current"
                            + " game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(ElementMatchers.named(METHOD)));
    }
}
