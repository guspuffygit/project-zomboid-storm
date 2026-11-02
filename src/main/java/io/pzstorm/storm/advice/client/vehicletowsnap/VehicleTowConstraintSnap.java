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
 * hitches coincide (the {@code positionTrailer} pattern). The snap translates without rotating, so
 * it is skipped when the two forward vectors are closer to perpendicular than {@link
 * #MIN_FORWARD_ALIGNMENT}. Vanilla's reconnect path accepts any orientation, and a translated but
 * crosswise pair would still start with a large angular error.
 *
 * <p>Fail-soft: any error permanently disables the helper and vanilla behavior resumes.
 */
public final class VehicleTowConstraintSnap {

    /** Above this hitch separation (squared, physics meters) the pair is snapped together. */
    public static final float SNAP_THRESHOLD_SQ = 0.25F * 0.25F;

    /**
     * Minimum |dot| of the two vehicles' forward vectors for a snap. Matches the alignment
     * vanilla's {@code canAttachTrailer} demands for a player-initiated attach.
     */
    public static final float MIN_FORWARD_ALIGNMENT = 0.6F;

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
                if (!Float.isFinite(sepSq) || sepSq <= SNAP_THRESHOLD_SQ) {
                    return;
                }
                float dotForward =
                        vehicleA.getForwardVector(new Vector3f())
                                .dot(vehicleB.getForwardVector(new Vector3f()));
                if (!Float.isFinite(dotForward) || Math.abs(dotForward) < MIN_FORWARD_ALIGNMENT) {
                    StormLogger.LOGGER.debug(
                            "Tow snap: skipped id={} and id={}, forward dot {} below {}",
                            vehicleA.getId(),
                            vehicleB.getId(),
                            dotForward,
                            MIN_FORWARD_ALIGNMENT);
                    return;
                }
                BaseVehicle target = null;
                float sign = 1.0F;
                if (vehicleB.getDriver() == null) {
                    target = vehicleB;
                } else if (vehicleA.getDriver() == null) {
                    target = vehicleA;
                    sign = -1.0F;
                }
                if (target == null) {
                    return;
                }
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
            } finally {
                BaseVehicle.releaseTransform(xfrm);
            }
        } catch (Throwable t) {
            disabled = true;
            StormLogger.LOGGER.error("VehicleTowConstraintSnap disabled after error", t);
        }
    }
}
