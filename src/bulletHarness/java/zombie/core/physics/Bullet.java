package zombie.core.physics;

import io.pzstorm.storm.bullet.trace.harness.HarnessUpcalls;
import java.nio.ByteBuffer;

/**
 * HARNESS STUB of the game's {@code zombie.core.physics.Bullet}: same natives (all made public so
 * the harness can call {@code ToBullet} directly) and the two static upcalls the library invokes,
 * routed to {@link HarnessUpcalls}. Exists only in the bulletHarness source set, which never sees
 * the game jar; generated from {@code BulletBackend}.
 */
public final class Bullet {

    private Bullet() {}

    public static native void ToBullet(ByteBuffer arg0);

    public static native String getPZBulletVersion();

    public static native void initPZBullet();

    public static native boolean isWorldInit();

    public static native void initWorld(
            int arg0, int arg1, int arg2, int arg3, int arg4, int arg5, boolean arg6);

    public static native void destroyWorld();

    public static native void activateChunkMap(int arg0, int arg1, int arg2, int arg3);

    public static native void deactivateChunkMap(int arg0);

    public static native void scrollChunkMap(int arg0, int arg1);

    public static native void setChunkMinMaxLevel(int arg0, int arg1, int arg2, int arg3);

    public static native void addVehicle(
            int arg0,
            float arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7,
            String arg8);

    public static native void removeVehicle(int arg0);

    public static native void controlVehicle(int arg0, float arg1, float arg2, float arg3);

    public static native void setVehicleActive(int arg0, boolean arg1);

    public static native void applyCentralForceToVehicle(
            int arg0, float arg1, float arg2, float arg3);

    public static native void applyTorqueToVehicle(int arg0, float arg1, float arg2, float arg3);

    public static native void teleportVehicle(
            int arg0,
            float arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7);

    public static native void setTireInflation(int arg0, int arg1, float arg2);

    public static native void setTireRemoved(int arg0, int arg1, boolean arg2);

    public static native void stepSimulation(float arg0, int arg1, float arg2);

    public static native int getVehicleCount();

    public static native int getVehiclePhysics(int arg0, float[] arg1);

    public static native int getOwnVehiclePhysics(int arg0, float[] arg1);

    public static native int setOwnVehiclePhysics(int arg0, float[] arg1, boolean arg2);

    public static native int setVehicleParams(int arg0, float[] arg1);

    public static native int setVehicleMass(int arg0, float arg1);

    public static native int getObjectPhysics(float[] arg0);

    public static native void createServerCell(int arg0, int arg1);

    public static native void removeServerCell(int arg0, int arg1);

    public static native int addPhysicsObject(float arg0, float arg1);

    public static native void defineVehicleScript(String arg0, float[] arg1);

    public static native void defineVehiclePhysicsMesh(String arg0, int arg1, float[] arg2);

    public static native void setVehicleVelocityMultiplier(int arg0, float arg1, float arg2);

    public static native int setVehicleStatic(int arg0, boolean arg1);

    public static native int addHingeConstraint(
            int arg0,
            int arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7);

    public static native int addPointConstraint(
            int arg0,
            int arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7);

    public static native int add6DofConstraint(
            int arg0,
            int arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7,
            float arg8,
            float arg9,
            float arg10,
            float arg11,
            float arg12,
            float arg13,
            float arg14,
            float arg15,
            float arg16,
            float arg17,
            float arg18,
            float arg19);

    public static native int addRopeConstraint(
            int arg0,
            int arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7,
            float arg8);

    public static native void setConstraintERP(int arg0, float arg1, int arg2);

    public static native void removeConstraint(int arg0);

    public static native void clearPhysicsMeshes();

    public static native void definePhysicsMesh(int arg0, boolean arg1, float[] arg2);

    public static native void initializeRagdollPose(
            int arg0, float[] arg1, float arg2, float arg3, float arg4, float arg5);

    public static native void initializeRagdollSkeleton(int arg0, int[] arg1);

    public static native void addRagdoll(
            int arg0,
            float arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7);

    public static native void removeRagdoll(int arg0);

    public static native int simulateRagdoll(int arg0, float[] arg1);

    public static native int simulateRagdollWithRigidBodyOutput(
            int arg0, float[] arg1, float[] arg2);

    public static native int updateSkeletonFromNetworkPhysics(int arg0, float[] arg1, float[] arg2);

    public static native void getCorrectedWorldSpace(int arg0, float[] arg1);

    public static native void setRagdollLocalTransformRotation(
            int arg0, float arg1, float arg2, float arg3, float arg4);

    public static native void updateRagdoll(
            int arg0,
            float arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7);

    public static native void updateRagdollSkeletonTransforms(int arg0, int arg1, float[] arg2);

    public static native void updateRagdollSkeletonPreviousTransforms(
            int arg0, int arg1, float arg2, float[] arg3);

    public static native int getRagdollSimulationState(int arg0);

    public static native void resetSkeletonPose(int arg0);

    public static native void setRagdollActive(int arg0, boolean arg1);

    public static native void drawDebugSingleBone(int arg0, boolean arg1);

    public static native void drawDebugRagdollSkeleton(int arg0, boolean arg1, boolean arg2);

    public static native void drawDebugRagdollBodyParts(int arg0, boolean arg1, boolean arg2);

    public static native void highlightRagdollBodyPart(int arg0, int arg1);

    public static native void applyForce(int arg0, int arg1, float[] arg2);

    public static native void applyImpulse(int arg0, int arg1, float[] arg2);

    public static native void detachConstraint(int arg0, int arg1);

    public static native void updateBallistics(int arg0, float arg1, float arg2, float arg3);

    public static native void updateBallisticsMuzzleAimDirection(
            int arg0, float arg1, float arg2, float arg3);

    public static native void setBallisticsSize(int arg0, float arg1);

    public static native void setBallisticsColor(int arg0, float arg1, float arg2, float arg3);

    public static native int getBallisticsTargets(int arg0, float arg1, int arg2, float[] arg3);

    public static native int getBallisticsTargetsSpreadData(
            int arg0, float arg1, float arg2, float arg3, int arg4, int arg5, float[] arg6);

    public static native int getBallisticsCameraTargets(
            int arg0, float arg1, int arg2, boolean arg3, float[] arg4);

    public static native void setBallisticsRange(int arg0, float arg1);

    public static native void removeBallistics(int arg0);

    public static native void updateBallisticsAimReticlePosition(
            int arg0, float arg1, float arg2, float arg3);

    public static native void updateBallisticsAimReticleRotation(
            int arg0, float arg1, float arg2, float arg3, float arg4);

    public static native void updateBallisticsAimReticleQuaternion(
            int arg0, float arg1, float arg2, float arg3, float arg4);

    public static native void updateBallisticsAimReticleRotate(
            int arg0, float arg1, float arg2, float arg3, float arg4);

    public static native void updateBallisticsTargetSkeleton(int arg0, int arg1, float[] arg2);

    public static native void updateBallisticsTarget(
            int arg0,
            float arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7,
            boolean arg8);

    public static native void setBallisticsTargetAxis(int arg0, float arg1, float arg2, float arg3);

    public static native int addBallisticsTarget(int arg0);

    public static native int removeBallisticsTarget(int arg0);

    public static native int getTargetedBodyPart(int arg0);

    public static native void setRagdollMass(float arg0);

    public static native boolean checkWheelCollision(int arg0, int arg1, int arg2);

    public static native boolean defineRagdollConstraints(float[] arg0, boolean arg1);

    public static native boolean defineRagdollAnchors(float[] arg0, boolean arg1);

    public static native boolean defineRagdollBodyPartInfo(float[] arg0, boolean arg1);

    public static native boolean defineRagdollBodyDynamics(float[] arg0, boolean arg1);

    public static native boolean setRagdollBodyDynamics(int arg0, float[] arg1);

    public static native boolean resetRagdollBodyDynamics(int arg0);

    public static native void setBallisticsTargetAdjustingShapeScale(
            float arg0, float arg1, float arg2);

    public static native void setBallisticsTargetAllPartsColor(float arg0, float arg1, float arg2);

    /** Upcall from the library (ChunkLevel::getLatestPhysicsShapesFromJava). */
    public static boolean updatePhysicsForLevelIfNeeded(int wx, int wy, int level) {
        return HarnessUpcalls.handler().updatePhysicsForLevelIfNeeded(wx, wy, level);
    }

    /** Upcall from the library (WorldSimulation::onVehicleConstraintImpulse). */
    public static void onVehicleConstraintImpulse(
            int constraintId, int vehicleIdA, int vehicleIdB, float impulse) {
        HarnessUpcalls.handler()
                .onVehicleConstraintImpulse(constraintId, vehicleIdA, vehicleIdB, impulse);
    }
}
