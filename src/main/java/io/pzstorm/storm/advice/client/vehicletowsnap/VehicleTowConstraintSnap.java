package io.pzstorm.storm.advice.client.vehicletowsnap;

import io.pzstorm.storm.logging.StormLogger;
import org.joml.Vector3f;
import zombie.core.physics.Transform;
import zombie.vehicles.BaseVehicle;

/**
 * Closes the hitch-point gap of a tow pair before the Bullet constraint is (re)created, so the
 * rigid zero-slack constraint never has a large initial error for the solver to correct with a
 * violent impulse.
 *
 * <p>Separation is measured on the exact pivots the constraint will use ({@code getTowingLocalPos}
 * / {@code getTowedByLocalPos} transformed by each vehicle's physics transform), all in physics
 * space — including the vertical axis, which vanilla's {@code canAttachTrailer} discards and which
 * is the usual launch vector after a chunk reload.
 *
 * <p>Above {@link #SNAP_THRESHOLD_SQ} the driverless vehicle of the pair is teleported so the
 * hitches coincide (the {@code positionTrailer} pattern). Any measurable separation also arms
 * vanilla's own attach softening: with {@code beginAttachTrailerMS} fresh, {@code
 * addPointConstraint} sets the constraint ERP to 0.02 and {@code checkTrailerAttachTime()} restores
 * 0.2 two seconds later, so residual error is pulled in gently instead of in one solver step.
 *
 * <p>Fail-soft: any error permanently disables the helper and vanilla behavior resumes.
 */
public final class VehicleTowConstraintSnap {

    /** Below this hitch separation (squared, physics meters) the attach is left untouched. */
    public static final float NOISE_EPSILON_SQ = 0.05F * 0.05F;

    /** Above this hitch separation (squared, physics meters) the pair is snapped together. */
    public static final float SNAP_THRESHOLD_SQ = 0.25F * 0.25F;

    public static volatile boolean disabled;

    private VehicleTowConstraintSnap() {}

    public static void beforeAttach(
            BaseVehicle vehicleA, BaseVehicle vehicleB, String attachmentA, String attachmentB) {
        if (disabled || vehicleB == null || vehicleA == vehicleB) {
            return;
        }
        try {
            if (vehicleA.getController() == null || vehicleB.getController() == null) {
                return;
            }
            Vector3f hitchA = vehicleA.getTowingLocalPos(attachmentA, new Vector3f());
            Vector3f hitchB = vehicleB.getTowedByLocalPos(attachmentB, new Vector3f());
            if (hitchA == null || hitchB == null) {
                return;
            }
            Transform xfrm = BaseVehicle.allocTransform();
            try {
                vehicleA.getWorldTransform(xfrm);
                xfrm.transform(hitchA);
                vehicleB.getWorldTransform(xfrm);
                xfrm.transform(hitchB);
                float dx = hitchA.x - hitchB.x;
                float dy = hitchA.y - hitchB.y;
                float dz = hitchA.z - hitchB.z;
                float sepSq = dx * dx + dy * dy + dz * dz;
                if (!Float.isFinite(sepSq) || sepSq <= NOISE_EPSILON_SQ) {
                    return;
                }
                if (sepSq > SNAP_THRESHOLD_SQ) {
                    BaseVehicle target = null;
                    float sign = 1.0F;
                    if (vehicleB.getDriver() == null) {
                        target = vehicleB;
                    } else if (vehicleA.getDriver() == null) {
                        target = vehicleA;
                        sign = -1.0F;
                    }
                    if (target != null) {
                        target.getWorldTransform(xfrm);
                        xfrm.origin.x += sign * dx;
                        xfrm.origin.y += sign * dy;
                        xfrm.origin.z += sign * dz;
                        target.setWorldTransform(xfrm);
                        // physics x/z map to world x/y; physics y is height
                        float newX = target.getX() + sign * dx;
                        float newY = target.getY() + sign * dz;
                        target.setX(newX);
                        target.setLastX(newX);
                        target.setY(newY);
                        target.setLastY(newY);
                        target.setCurrentSquareFromPosition(newX, newY, target.getZ());
                        StormLogger.LOGGER.info(
                                "Tow snap: moved vehicle id={} ({}) onto hitch of id={} ({}),"
                                        + " separation was {} m (dx={} dz={} dy={})",
                                target.getId(),
                                target.getScriptName(),
                                (target == vehicleB ? vehicleA : vehicleB).getId(),
                                (target == vehicleB ? vehicleA : vehicleB).getScriptName(),
                                Math.sqrt(sepSq),
                                dx,
                                dz,
                                dy);
                    }
                }
                // Arm vanilla's attach softening so addPointConstraint applies ERP 0.02 and
                // checkTrailerAttachTime() ramps back to 0.2 after 2 s.
                if (!vehicleA.isAttachingTrailer()) {
                    vehicleA.beginAttachingTrailer();
                }
            } finally {
                BaseVehicle.releaseTransform(xfrm);
            }
        } catch (Throwable t) {
            disabled = true;
            StormLogger.LOGGER.error("VehicleTowConstraintSnap disabled after error", t);
        }
    }
}
