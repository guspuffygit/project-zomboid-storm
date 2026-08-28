package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Stops {@code BaseVehicle.onEngineStateChanged} from throwing NPE on multiplayer
 * clients, which aborts {@code VehicleUpdatePacket.parse} mid-body and silently drops every field
 * after the engine section — including the authoritative passenger/seat list.
 *
 * <p>Root cause: vanilla {@code checkVehicleSoundsExists()} only creates {@code VehicleSounds} in
 * single player, but the client branch of {@code onEngineStateChanged} dereferences {@code
 * getVehicleSounds()} unconditionally. On MP clients the instance is normally attached by the
 * vehicle-network-sound {@code VehicleState}, but only once the vehicle is in {@code
 * cell.vehicles}; engine-state packets arriving before that throw.
 *
 * <p>The advice hooks {@code checkVehicleSoundsExists()} entry and creates the instance through
 * public {@code setVehicleSounds}, which the network sound handover later replaces cleanly.
 *
 * <p>Why a client bytecode patch: the NPE fires inside the client's packet-parse path ({@code
 * VehicleEngine.parse} → {@code onEngineStateChanged}) before any Lua event, and the private {@code
 * vehicleSounds} field cannot be initialized from Lua or from the server. Fail-soft: the helper
 * permanently disables itself on any error and vanilla behavior resumes.
 */
public class VehicleSoundsClientCreatePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.client.vehiclesounds.";

    public VehicleSoundsClientCreatePatch() {
        super("zombie.vehicles.BaseVehicle");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "VehicleSoundsClientCreateAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("checkVehicleSoundsExists")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
