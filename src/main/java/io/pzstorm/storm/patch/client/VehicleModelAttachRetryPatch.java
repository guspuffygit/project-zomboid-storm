package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Repairs vehicles that exist with physics/collision but were never given a render
 * model — the permanent "invisible car you can crash into".
 *
 * <p>{@code BaseVehicle.createPhysics} sets {@code createdModel = true} even when {@code
 * ModelManager.addVehicle} silently fails to create a model slot (manager not created, model or
 * texture not loaded yet — routinely the case while a high-population server streams many vehicle
 * types at once). {@code IsoSprite.hasActiveModel()} then stays false forever: the vehicle
 * collides, casts nothing, and vanilla only re-attempts the attach when a part model changes.
 *
 * <p>The advice hooks {@code BaseVehicle.update()} exit and, when {@code sprite.modelSlot} is null,
 * re-runs {@code ModelManager.addVehicle} throttled to once per 2&nbsp;s per vehicle.
 *
 * <p>Why a client bytecode patch: the failure is inside the client render pipeline — the server
 * cannot observe it, and no Lua event fires between {@code createPhysics} and rendering. Fail-soft:
 * the helper permanently disables itself on any error and vanilla behavior resumes; a broken
 * reflection handle on a future PZ update disables it at class-init with a logged error.
 */
public class VehicleModelAttachRetryPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.client.vehiclemodelretry.";

    public VehicleModelAttachRetryPatch() {
        super("zombie.vehicles.BaseVehicle");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "VehicleModelAttachRetryAdvice").resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("update")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
