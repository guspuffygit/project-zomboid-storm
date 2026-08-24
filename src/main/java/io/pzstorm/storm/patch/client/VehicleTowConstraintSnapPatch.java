package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Prevents the "trailer launches the car into the stratosphere" bug.
 *
 * <p>Towing couples two vehicles with a rigid Bullet 6-DoF constraint (zero linear slack for
 * trailers) built from local-space hitch pivots. Whenever the constraint is created while the two
 * hitch points are not actually coincident in world space, the solver applies a corrective impulse
 * proportional to the separation — enough to launch both vehicles, damage them and kill the
 * passengers. Vanilla creates the constraint at inconsistent positions on three paths that all
 * funnel through the 5-arg {@code BaseVehicle.addPointConstraint}:
 *
 * <ul>
 *   <li>chunk-reload reconnect ({@code tryReconnectToTowedVehicle}) — {@code canAttachTrailer}
 *       ignores vertical separation entirely and accepts up to {@code sqrt(10)} tiles of horizontal
 *       separation on reconnect (10x the fresh-attach tolerance);
 *   <li>seat enter/exit ({@code authorizationServerOnSeat}, relayed to clients via {@code
 *       VehicleTowingAttachPacket}) — rebuilds the constraint with no distance check at all;
 *   <li>fresh attach paths that skip the {@code beginAttachingTrailer()} timed action and therefore
 *       miss vanilla's soft-ERP ramp, welding at full stiffness on frame one.
 * </ul>
 *
 * <p>Vanilla's impulse-based auto-detach ({@code Bullet.onVehicleConstraintImpulse}) is suppressed
 * for 2 s after any constraint change — exactly the window in which the snap impulse occurs.
 *
 * <p>The advice hooks {@code addPointConstraint} entry and, when the hitch points are separated,
 * teleports the driverless vehicle of the pair onto the hitch (the {@code positionTrailer} pattern:
 * {@code setWorldTransform} then constrain) and arms vanilla's own soft-ERP ramp so any residual
 * error is corrected gently.
 *
 * <p>Why a client bytecode patch: the constraint exists only on client JVMs — {@code
 * addPointConstraint} skips all {@code Bullet.add*Constraint} calls when {@code GameServer.server},
 * and the launch impulse is generated inside the driving client's native solver, unreachable from
 * the server or from Lua. Fail-soft: the helper permanently disables itself on any error and
 * vanilla behavior resumes.
 */
public class VehicleTowConstraintSnapPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.client.vehicletowsnap.";

    public VehicleTowConstraintSnapPatch() {
        super("zombie.vehicles.BaseVehicle");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "VehicleTowConstraintSnapAdvice").resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("addPointConstraint")
                                        .and(ElementMatchers.takesArguments(5))));
    }
}
