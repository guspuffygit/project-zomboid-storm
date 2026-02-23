package io.pzstorm.storm.vehicle;

import lombok.Getter;
import lombok.Setter;
import org.joml.Vector3f;
import zombie.input.Mouse;
import zombie.iso.IsoUtils;
import zombie.vehicles.BaseVehicle;

public final class MouseSteeringCalculator {

    /** Angular dead zone in radians (~3 degrees). Below this, steering = 0. */
    @Setter @Getter private static float deadZoneRad = 0.05f;

    /** Angle (radians) at which steering saturates to full lock (45 degrees). */
    @Setter @Getter private static float fullLockAngleRad = (float) (Math.PI / 4.0);

    /**
     * Minimum squared distance (world tiles) between vehicle and mouse to avoid erratic behavior.
     */
    @Setter @Getter private static float minDistanceSq = 1.0f;

    public static float calculateMouseSteering(BaseVehicle vehicle) {
        int mx = Mouse.getX();
        int my = Mouse.getY();

        float floor = (float) Math.floor(vehicle.getZ());
        float mouseWorldX = IsoUtils.XToIso(mx, my, floor);
        float mouseWorldY = IsoUtils.YToIso(mx, my, floor);

        float dx = mouseWorldX - vehicle.getX();
        float dy = mouseWorldY - vehicle.getY();

        float distSq = dx * dx + dy * dy;
        if (distSq < minDistanceSq) {
            return 0.0f;
        }

        float angleToMouse = (float) Math.atan2(dy, dx);

        Vector3f forward = vehicle.getForwardVector(new Vector3f());
        float vehicleAngle = (float) Math.atan2(forward.z, forward.x);

        float angleDiff = normalizeAngle(angleToMouse - vehicleAngle);

        if (Math.abs(angleDiff) < deadZoneRad) {
            return 0.0f;
        }

        return Math.max(-1.0f, Math.min(1.0f, angleDiff / fullLockAngleRad));
    }

    private static float normalizeAngle(float angle) {
        while (angle > Math.PI) {
            angle -= 2.0f * (float) Math.PI;
        }
        while (angle < -Math.PI) {
            angle += 2.0f * (float) Math.PI;
        }
        return angle;
    }
}
