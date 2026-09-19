package io.pzstorm.storm.patch.bullet;

import io.pzstorm.storm.bullet.trace.BulletApi;

/**
 * Bodies of the isolated {@code Bullet} copy's upcalls: record, then run the game class's own
 * implementation (so chunk physics comes from the live world and nested {@code ToBullet} calls go
 * through the recording wrappers). Exceptions propagate to the library, which clears them, as with
 * the unpatched game.
 */
public final class BulletRecordUpcalls {

    private BulletRecordUpcalls() {}

    public static boolean updatePhysicsForLevelIfNeeded(int wx, int wy, int level)
            throws Throwable {
        Object r =
                BulletRecorder.upcall(
                        BulletApi.UPDATE_PHYSICS_FOR_LEVEL_IF_NEEDED, new Object[] {wx, wy, level});
        return r instanceof Boolean b && b;
    }

    public static void onVehicleConstraintImpulse(
            int constraintId, int vidA, int vidB, float impulse) throws Throwable {
        BulletRecorder.upcall(
                BulletApi.ON_VEHICLE_CONSTRAINT_IMPULSE,
                new Object[] {constraintId, vidA, vidB, impulse});
    }
}
