package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Fixes the pending-flag clobber in {@code VehicleManager.sendVehicleRequest}.
 *
 * <p>{@code sendVehicleRequest} stores into {@code GameClient.connection.vehicleRequests} with
 * {@code put}, so two requests for the same vehicle inside one 100&nbsp;ms flush window keep only
 * the second flag. The losing combination that matters is Full ({@code 1}, queued by every
 * invisible-vehicle recovery path) overwritten by the 1&nbsp;Hz Passengers housekeeping request
 * ({@code 16384}, queued by {@code clientUpdate} for every known vehicle) — the full update the
 * client depends on to materialize a missing car is silently never requested. The advice OR-merges
 * the pending flags instead.
 *
 * <p>Why a client bytecode patch: the clobber happens inside {@code VehicleManager}, before any Lua
 * surface sees the request; there is no event or Lua hook between {@code sendVehicleRequest} call
 * sites (all in Java packet-parse paths) and the map write. Fail-soft: the advice only widens the
 * flag argument; on any unexpected state it leaves the argument untouched and vanilla behavior
 * proceeds.
 */
public class VehicleRequestMergeFlagsPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.client.vehiclerequestmerge.";

    public VehicleRequestMergeFlagsPatch() {
        super("zombie.vehicles.VehicleManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "VehicleRequestMergeAdvice").resolve(), locator)
                        .on(ElementMatchers.named("sendVehicleRequest")));
    }
}
