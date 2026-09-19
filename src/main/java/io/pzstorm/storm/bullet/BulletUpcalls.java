// The five JNI upcalls libPZBullet makes into Java (PORTING.md "JNI surface & upcalls"):
//   ChunkLevel::getLatestPhysicsShapesFromJava ->
// zombie.core.physics.Bullet.updatePhysicsForLevelIfNeeded(III)Z
//   WorldSimulation::onVehicleConstraintImpulse ->
// zombie.core.physics.Bullet.onVehicleConstraintImpulse(IIIF)V
//   PZDebugLog::init / logInternal             ->
// zombie.debug.DebugLog.nativeLog(String,String,String)V
//   SkeletonBone::GetName / GetJavaOrdinal      ->
// zombie.core.skinnedmodel.model.SkeletonBone.getBoneName/getBoneOrdinal
// The port makes these calls only through the call* helpers below. Every native call site follows
// the upcall with
// ExceptionCheck/ExceptionClear (a pending Java exception is cleared, the JNI call result is
// 0/false/null), which the
// helpers mirror by catching the Throwable and returning the JNI default.
package io.pzstorm.storm.bullet;

public final class BulletUpcalls {

    private BulletUpcalls() {}

    public interface Target {
        boolean updatePhysicsForLevelIfNeeded(int wx, int wy, int level);

        void onVehicleConstraintImpulse(
                int constraintId, int vehicleIdA, int vehicleIdB, float impulse);

        /** Same argument order the native passes: (logType, logSeverity, logText). */
        void nativeLog(String a, String b, String c);

        String getBoneName(int ordinal);

        int getBoneOrdinal(String name);
    }

    /** Default target: the real game classes. */
    public static final class GameTarget implements Target {

        public static final GameTarget INSTANCE = new GameTarget();

        private GameTarget() {}

        @Override
        public boolean updatePhysicsForLevelIfNeeded(int wx, int wy, int level) {
            return zombie.core.physics.Bullet.updatePhysicsForLevelIfNeeded(wx, wy, level);
        }

        @Override
        public void onVehicleConstraintImpulse(
                int constraintId, int vehicleIdA, int vehicleIdB, float impulse) {
            zombie.core.physics.Bullet.onVehicleConstraintImpulse(
                    constraintId, vehicleIdA, vehicleIdB, impulse);
        }

        @Override
        public void nativeLog(String a, String b, String c) {
            zombie.debug.DebugLog.nativeLog(a, b, c);
        }

        @Override
        public String getBoneName(int ordinal) {
            return zombie.core.skinnedmodel.model.SkeletonBone.getBoneName(ordinal);
        }

        @Override
        public int getBoneOrdinal(String name) {
            return zombie.core.skinnedmodel.model.SkeletonBone.getBoneOrdinal(name);
        }
    }

    public static volatile Target target = GameTarget.INSTANCE;

    /** CallStaticBooleanMethod + exception clear: false when the call threw. */
    public static boolean callUpdatePhysicsForLevelIfNeeded(int wx, int wy, int level) {
        try {
            return target.updatePhysicsForLevelIfNeeded(wx, wy, level);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void callOnVehicleConstraintImpulse(
            int constraintId, int vehicleIdA, int vehicleIdB, float impulse) {
        try {
            target.onVehicleConstraintImpulse(constraintId, vehicleIdA, vehicleIdB, impulse);
        } catch (Throwable t) {
            // JNI: ExceptionClear
        }
    }

    public static void callNativeLog(String a, String b, String c) {
        try {
            target.nativeLog(a, b, c);
        } catch (Throwable t) {
            // JNI: ExceptionClear
        }
    }

    /** CallStaticObjectMethod + exception clear: null when the call threw. */
    public static String callGetBoneName(int ordinal) {
        try {
            return target.getBoneName(ordinal);
        } catch (Throwable t) {
            return null;
        }
    }

    /** CallStaticIntMethod + exception clear: 0 when the call threw. */
    public static int callGetBoneOrdinal(String name) {
        try {
            return target.getBoneOrdinal(name);
        } catch (Throwable t) {
            return 0;
        }
    }
}
