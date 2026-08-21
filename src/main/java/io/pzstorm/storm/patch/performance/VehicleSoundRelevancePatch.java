package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Hoists the connection-independent half of {@code Connection.isRelevant(BaseVehicle)} out of the
 * per-connection vehicle walk in {@code zombie.vehicleNetworkSound.server.Manager.update()} — see
 * {@link io.pzstorm.storm.vehicles.StormVehicleSoundRelevance}. Two advices: {@code
 * ManagerUpdateAdvice} builds/drops the per-tick noisy-vehicle snapshot around {@code update()},
 * {@code ManagerGetVehiclesRelevantAdvice} answers the private {@code
 * getVehiclesRelevantToConnection} from it. Profiled at ~4.1% of the main thread (1,100 vehicles ×
 * 70 connections). Server-only by registration gate ({@code Manager} is server-side code).
 *
 * <p>Re-validate on every game update: the hoist is exact only while {@code Connection.isRelevant}
 * keeps its predicate set (alarm 500 / beeper 150 / door alarm 50 / engine-not-idle 200 / horn 500
 * / siren 500, then {@code RelevantTo}) and nothing inside {@code Manager.update()} mutates vehicle
 * state between connections.
 *
 * <p>Kill switch: the {@code Storm.VehicleSoundRelevanceFastPath} sandbox option; any throwable
 * latches the fast path off for the session.
 */
public class VehicleSoundRelevancePatch extends StormClassTransformer {

    private static final String TARGET = "zombie.vehicleNetworkSound.server.Manager";
    private static final String PKG = "io.pzstorm.storm.advice.vehiclesoundrelevance.";

    public VehicleSoundRelevancePatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        requireDeclared(target, "update");
        requireDeclared(target, "getVehiclesRelevantToConnection");
        return builder.visit(
                        Advice.to(typePool.describe(PKG + "ManagerUpdateAdvice").resolve(), locator)
                                .on(ElementMatchers.named("update")))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "ManagerGetVehiclesRelevantAdvice")
                                                .resolve(),
                                        locator)
                                .on(ElementMatchers.named("getVehiclesRelevantToConnection")));
    }

    private static void requireDeclared(TypeDescription target, String method) {
        if (target.getDeclaredMethods().filter(ElementMatchers.named(method)).isEmpty()) {
            throw new IllegalStateException(
                    "VehicleSoundRelevancePatch: "
                            + TARGET
                            + " no longer declares "
                            + method
                            + " — the name-string hook would silently no-op and reintroduce the"
                            + " per-connection whole-vehicle-set scan. Re-verify the patch against"
                            + " the current game source.");
        }
    }
}
