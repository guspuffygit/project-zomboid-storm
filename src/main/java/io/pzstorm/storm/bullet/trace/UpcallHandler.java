package io.pzstorm.storm.bullet.trace;

/**
 * The five places the native library calls back into Java (see PORTING.md "JNI surface & upcalls").
 * Mirrors {@code io.pzstorm.storm.bullet.BulletUpcalls.Target} method for method so a reflective
 * proxy can bridge the two.
 */
public interface UpcallHandler {

    /** {@code zombie.core.physics.Bullet.updatePhysicsForLevelIfNeeded(III)Z}. */
    boolean updatePhysicsForLevelIfNeeded(int wx, int wy, int level);

    /** {@code zombie.core.physics.Bullet.onVehicleConstraintImpulse(IIIF)V}. */
    void onVehicleConstraintImpulse(
            int constraintId, int vehicleIdA, int vehicleIdB, float impulse);

    /** {@code zombie.debug.DebugLog.nativeLog(String,String,String)V}. */
    void nativeLog(String logType, String logSeverity, String logText);

    /** {@code zombie.core.skinnedmodel.model.SkeletonBone.getBoneName(I)String}. */
    String getBoneName(int ordinal);

    /** {@code zombie.core.skinnedmodel.model.SkeletonBone.getBoneOrdinal(String)I}. */
    int getBoneOrdinal(String name);
}
