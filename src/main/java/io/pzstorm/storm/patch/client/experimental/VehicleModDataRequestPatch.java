package io.pzstorm.storm.patch.client.experimental;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL — client-only.
 *
 * <p>When the server starts broadcasting {@code ObjectModDataPacket} for a vehicle that has just
 * entered the player's relevance range, the modData packets may arrive before the initial vehicle
 * sync ({@code VehicleUpdatePacket}) populates {@code VehicleManager.vehicleMap} on the client.
 * Inside {@link zombie.network.fields.MovingObject#getObject()} the {@code objectType == 5} branch
 * then calls {@code VehicleManager.getVehicleByID(objectId)} and returns {@code null}, producing a
 * spam of {@code ObjectModDataPacket.parse: object is null} warnings until the periodic vehicle
 * resync catches up.
 *
 * <p>This patch hooks {@code MovingObject.getObject()} on exit. When the lookup returned {@code
 * null} for a vehicle (objectType=5), the advice fires a throttled {@code
 * VehicleManager.sendVehicleRequest(vehicleId, 16384)} so the client receives the missing vehicle
 * state immediately rather than waiting on the next periodic sweep. The {@code 16384} flag is PZ's
 * own "send me the full vehicle" sentinel (see {@code VehicleRequestPacket.parse}).
 */
public class VehicleModDataRequestPatch extends StormClassTransformer {

    private static final String PKG =
            "io.pzstorm.storm.advice.client.experimental.vehiclemodatarequest.";

    public VehicleModDataRequestPatch() {
        super("zombie.network.fields.MovingObject");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "VehicleModDataRequestAdvice").resolve(), locator)
                        .on(ElementMatchers.named("getObject")));
    }
}
