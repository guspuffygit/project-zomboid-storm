package io.pzstorm.storm.bullet.trace;

import java.nio.ByteBuffer;

/**
 * One method per {@code native} of {@code zombie.core.physics.Bullet} (PZ 42.20.4), same name,
 * parameter types and return type. Implemented by the native library (harness stub class), by the
 * Java port ({@link JavaBackend}) and by recording/replaying wrappers. Parameter names follow the
 * game's decompiled {@code varN} numbering; see the game's callers for their meaning.
 *
 * <p>Generated from {@code Bullet.java}; {@code BulletBackendSignatureTest} fails if the game's
 * native set drifts from this interface. This package must depend on the JDK only: it runs in the
 * native-replay container without the game jar.
 */
public interface BulletBackend {
    void ToBullet(ByteBuffer arg0);

    String getPZBulletVersion();

    void initPZBullet();

    boolean isWorldInit();

    void initWorld(int arg0, int arg1, int arg2, int arg3, int arg4, int arg5, boolean arg6);

    void destroyWorld();

    void activateChunkMap(int arg0, int arg1, int arg2, int arg3);

    void deactivateChunkMap(int arg0);

    void scrollChunkMap(int arg0, int arg1);

    void setChunkMinMaxLevel(int arg0, int arg1, int arg2, int arg3);

    void addVehicle(
            int arg0,
            float arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7,
            String arg8);

    void removeVehicle(int arg0);

    void controlVehicle(int arg0, float arg1, float arg2, float arg3);

    void setVehicleActive(int arg0, boolean arg1);

    void applyCentralForceToVehicle(int arg0, float arg1, float arg2, float arg3);

    void applyTorqueToVehicle(int arg0, float arg1, float arg2, float arg3);

    void teleportVehicle(
            int arg0,
            float arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7);

    void setTireInflation(int arg0, int arg1, float arg2);

    void setTireRemoved(int arg0, int arg1, boolean arg2);

    void stepSimulation(float arg0, int arg1, float arg2);

    int getVehicleCount();

    int getVehiclePhysics(int arg0, float[] arg1);

    int getOwnVehiclePhysics(int arg0, float[] arg1);

    int setOwnVehiclePhysics(int arg0, float[] arg1, boolean arg2);

    int setVehicleParams(int arg0, float[] arg1);

    int setVehicleMass(int arg0, float arg1);

    int getObjectPhysics(float[] arg0);

    void createServerCell(int arg0, int arg1);

    void removeServerCell(int arg0, int arg1);

    int addPhysicsObject(float arg0, float arg1);

    void defineVehicleScript(String arg0, float[] arg1);

    void defineVehiclePhysicsMesh(String arg0, int arg1, float[] arg2);

    void setVehicleVelocityMultiplier(int arg0, float arg1, float arg2);

    int setVehicleStatic(int arg0, boolean arg1);

    int addHingeConstraint(
            int arg0,
            int arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7);

    int addPointConstraint(
            int arg0,
            int arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7);

    int add6DofConstraint(
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

    int addRopeConstraint(
            int arg0,
            int arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7,
            float arg8);

    void setConstraintERP(int arg0, float arg1, int arg2);

    void removeConstraint(int arg0);

    void clearPhysicsMeshes();

    void definePhysicsMesh(int arg0, boolean arg1, float[] arg2);

    void initializeRagdollPose(
            int arg0, float[] arg1, float arg2, float arg3, float arg4, float arg5);

    void initializeRagdollSkeleton(int arg0, int[] arg1);

    void addRagdoll(
            int arg0,
            float arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7);

    void removeRagdoll(int arg0);

    int simulateRagdoll(int arg0, float[] arg1);

    int simulateRagdollWithRigidBodyOutput(int arg0, float[] arg1, float[] arg2);

    int updateSkeletonFromNetworkPhysics(int arg0, float[] arg1, float[] arg2);

    void getCorrectedWorldSpace(int arg0, float[] arg1);

    void setRagdollLocalTransformRotation(int arg0, float arg1, float arg2, float arg3, float arg4);

    void updateRagdoll(
            int arg0,
            float arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7);

    void updateRagdollSkeletonTransforms(int arg0, int arg1, float[] arg2);

    void updateRagdollSkeletonPreviousTransforms(int arg0, int arg1, float arg2, float[] arg3);

    int getRagdollSimulationState(int arg0);

    void resetSkeletonPose(int arg0);

    void setRagdollActive(int arg0, boolean arg1);

    void drawDebugSingleBone(int arg0, boolean arg1);

    void drawDebugRagdollSkeleton(int arg0, boolean arg1, boolean arg2);

    void drawDebugRagdollBodyParts(int arg0, boolean arg1, boolean arg2);

    void highlightRagdollBodyPart(int arg0, int arg1);

    void applyForce(int arg0, int arg1, float[] arg2);

    void applyImpulse(int arg0, int arg1, float[] arg2);

    void detachConstraint(int arg0, int arg1);

    void updateBallistics(int arg0, float arg1, float arg2, float arg3);

    void updateBallisticsMuzzleAimDirection(int arg0, float arg1, float arg2, float arg3);

    void setBallisticsSize(int arg0, float arg1);

    void setBallisticsColor(int arg0, float arg1, float arg2, float arg3);

    int getBallisticsTargets(int arg0, float arg1, int arg2, float[] arg3);

    int getBallisticsTargetsSpreadData(
            int arg0, float arg1, float arg2, float arg3, int arg4, int arg5, float[] arg6);

    int getBallisticsCameraTargets(int arg0, float arg1, int arg2, boolean arg3, float[] arg4);

    void setBallisticsRange(int arg0, float arg1);

    void removeBallistics(int arg0);

    void updateBallisticsAimReticlePosition(int arg0, float arg1, float arg2, float arg3);

    void updateBallisticsAimReticleRotation(
            int arg0, float arg1, float arg2, float arg3, float arg4);

    void updateBallisticsAimReticleQuaternion(
            int arg0, float arg1, float arg2, float arg3, float arg4);

    void updateBallisticsAimReticleRotate(int arg0, float arg1, float arg2, float arg3, float arg4);

    void updateBallisticsTargetSkeleton(int arg0, int arg1, float[] arg2);

    void updateBallisticsTarget(
            int arg0,
            float arg1,
            float arg2,
            float arg3,
            float arg4,
            float arg5,
            float arg6,
            float arg7,
            boolean arg8);

    void setBallisticsTargetAxis(int arg0, float arg1, float arg2, float arg3);

    int addBallisticsTarget(int arg0);

    int removeBallisticsTarget(int arg0);

    int getTargetedBodyPart(int arg0);

    void setRagdollMass(float arg0);

    boolean checkWheelCollision(int arg0, int arg1, int arg2);

    boolean defineRagdollConstraints(float[] arg0, boolean arg1);

    boolean defineRagdollAnchors(float[] arg0, boolean arg1);

    boolean defineRagdollBodyPartInfo(float[] arg0, boolean arg1);

    boolean defineRagdollBodyDynamics(float[] arg0, boolean arg1);

    boolean setRagdollBodyDynamics(int arg0, float[] arg1);

    boolean resetRagdollBodyDynamics(int arg0);

    void setBallisticsTargetAdjustingShapeScale(float arg0, float arg1, float arg2);

    void setBallisticsTargetAllPartsColor(float arg0, float arg1, float arg2);
}
