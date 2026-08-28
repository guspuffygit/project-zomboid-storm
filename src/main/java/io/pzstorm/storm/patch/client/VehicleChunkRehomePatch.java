package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Prevents the "frozen car with a player inside" desync.
 *
 * <p>When the chunk a vehicle is filed under scrolls out of the client's loaded area while a local
 * player is aboard, {@code BaseVehicle.removeFromWorld()} refuses to delete the vehicle (occupied
 * by a local player) but the unload has already nulled its {@code current} square. Every subsequent
 * {@code update()} then hits the {@code !GameServer.server && chunk.refs.isEmpty()} branch, calls
 * {@code removeFromWorld()} again (still a no-op) and returns before {@code super.update()} — the
 * vehicle permanently stops moving and simulating on that client while the server keeps driving it,
 * leaving the aboard player blind to their real position. Vanilla's re-home block later in {@code
 * update()} can never run because it requires {@code current} to be non-null.
 *
 * <p>The advice hooks {@code update()} entry and re-files such a vehicle under the live chunk at
 * its actual position (loaded in practice, since the chunk map is centered on the aboard player).
 *
 * <p>Why a client bytecode patch: the stranded state exists only in the client's {@code
 * IsoChunk}/{@code BaseVehicle} bookkeeping — the server view is correct — and the freeze happens
 * inside {@code BaseVehicle.update()} before any Lua-visible event fires, so neither a server-side
 * change nor client Lua can intercept it. Fail-soft: the helper permanently disables itself on any
 * error and vanilla behavior resumes.
 */
public class VehicleChunkRehomePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.client.vehiclechunkrehome.";

    public VehicleChunkRehomePatch() {
        super("zombie.vehicles.BaseVehicle");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "VehicleChunkRehomeAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("update")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
