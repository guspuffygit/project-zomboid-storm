// Java port facade of libPZBullet's JNI surface (zombie.core.physics.Bullet natives, PZBullet
// 1.0.0.28).
// One public static per native, same name and Java signature as zombie/core/physics/Bullet.java,
// each a
// one-line delegation to the owning pz.jni.*Natives class. (Bullet.ToBullet is private in the game;
// public here.)
// Java_zombie_core_physics_Bullet_Start/Finish/throwPhysicsObject are exported by the library but
// not declared in
// Bullet.java; they live in WorldNatives only.
package io.pzstorm.storm.bullet;

import io.pzstorm.storm.bullet.pz.jni.BallisticsNatives;
import io.pzstorm.storm.bullet.pz.jni.RagdollNatives;
import io.pzstorm.storm.bullet.pz.jni.VehicleNatives;
import io.pzstorm.storm.bullet.pz.jni.WorldNatives;
import java.nio.ByteBuffer;

public final class StormBullet {

    private StormBullet() {}

    public static void ToBullet(ByteBuffer a0) {
        WorldNatives.ToBullet(a0);
    }

    public static String getPZBulletVersion() {
        return WorldNatives.getPZBulletVersion();
    }

    public static void initPZBullet() {
        WorldNatives.initPZBullet();
    }

    public static boolean isWorldInit() {
        return WorldNatives.isWorldInit();
    }

    public static void initWorld(int a0, int a1, int a2, int a3, int a4, int a5, boolean a6) {
        WorldNatives.initWorld(a0, a1, a2, a3, a4, a5, a6);
    }

    public static void destroyWorld() {
        WorldNatives.destroyWorld();
    }

    public static void activateChunkMap(int a0, int a1, int a2, int a3) {
        WorldNatives.activateChunkMap(a0, a1, a2, a3);
    }

    public static void deactivateChunkMap(int a0) {
        WorldNatives.deactivateChunkMap(a0);
    }

    public static void scrollChunkMap(int a0, int a1) {
        WorldNatives.scrollChunkMap(a0, a1);
    }

    public static void setChunkMinMaxLevel(int a0, int a1, int a2, int a3) {
        WorldNatives.setChunkMinMaxLevel(a0, a1, a2, a3);
    }

    public static void addVehicle(
            int a0,
            float a1,
            float a2,
            float a3,
            float a4,
            float a5,
            float a6,
            float a7,
            String a8) {
        VehicleNatives.addVehicle(a0, a1, a2, a3, a4, a5, a6, a7, a8);
    }

    public static void removeVehicle(int a0) {
        VehicleNatives.removeVehicle(a0);
    }

    public static void controlVehicle(int a0, float a1, float a2, float a3) {
        VehicleNatives.controlVehicle(a0, a1, a2, a3);
    }

    public static void setVehicleActive(int a0, boolean a1) {
        VehicleNatives.setVehicleActive(a0, a1);
    }

    public static void applyCentralForceToVehicle(int a0, float a1, float a2, float a3) {
        VehicleNatives.applyCentralForceToVehicle(a0, a1, a2, a3);
    }

    public static void applyTorqueToVehicle(int a0, float a1, float a2, float a3) {
        VehicleNatives.applyTorqueToVehicle(a0, a1, a2, a3);
    }

    public static void teleportVehicle(
            int a0, float a1, float a2, float a3, float a4, float a5, float a6, float a7) {
        VehicleNatives.teleportVehicle(a0, a1, a2, a3, a4, a5, a6, a7);
    }

    public static void setTireInflation(int a0, int a1, float a2) {
        VehicleNatives.setTireInflation(a0, a1, a2);
    }

    public static void setTireRemoved(int a0, int a1, boolean a2) {
        VehicleNatives.setTireRemoved(a0, a1, a2);
    }

    public static void stepSimulation(float a0, int a1, float a2) {
        WorldNatives.stepSimulation(a0, a1, a2);
    }

    public static int getVehicleCount() {
        return VehicleNatives.getVehicleCount();
    }

    public static int getVehiclePhysics(int a0, float[] a1) {
        return VehicleNatives.getVehiclePhysics(a0, a1);
    }

    public static int getOwnVehiclePhysics(int a0, float[] a1) {
        return VehicleNatives.getOwnVehiclePhysics(a0, a1);
    }

    public static int setOwnVehiclePhysics(int a0, float[] a1, boolean a2) {
        return VehicleNatives.setOwnVehiclePhysics(a0, a1, a2);
    }

    public static int setVehicleParams(int a0, float[] a1) {
        return VehicleNatives.setVehicleParams(a0, a1);
    }

    public static int setVehicleMass(int a0, float a1) {
        return VehicleNatives.setVehicleMass(a0, a1);
    }

    public static int getObjectPhysics(float[] a0) {
        return WorldNatives.getObjectPhysics(a0);
    }

    public static void createServerCell(int a0, int a1) {
        WorldNatives.createServerCell(a0, a1);
    }

    public static void removeServerCell(int a0, int a1) {
        WorldNatives.removeServerCell(a0, a1);
    }

    public static int addPhysicsObject(float a0, float a1) {
        return WorldNatives.addPhysicsObject(a0, a1);
    }

    public static void defineVehicleScript(String a0, float[] a1) {
        VehicleNatives.defineVehicleScript(a0, a1);
    }

    public static void defineVehiclePhysicsMesh(String a0, int a1, float[] a2) {
        VehicleNatives.defineVehiclePhysicsMesh(a0, a1, a2);
    }

    public static void setVehicleVelocityMultiplier(int a0, float a1, float a2) {
        VehicleNatives.setVehicleVelocityMultiplier(a0, a1, a2);
    }

    public static int setVehicleStatic(int a0, boolean a1) {
        return VehicleNatives.setVehicleStatic(a0, a1);
    }

    public static int addHingeConstraint(
            int a0, int a1, float a2, float a3, float a4, float a5, float a6, float a7) {
        return VehicleNatives.addHingeConstraint(a0, a1, a2, a3, a4, a5, a6, a7);
    }

    public static int addPointConstraint(
            int a0, int a1, float a2, float a3, float a4, float a5, float a6, float a7) {
        return VehicleNatives.addPointConstraint(a0, a1, a2, a3, a4, a5, a6, a7);
    }

    public static int add6DofConstraint(
            int a0,
            int a1,
            float a2,
            float a3,
            float a4,
            float a5,
            float a6,
            float a7,
            float a8,
            float a9,
            float a10,
            float a11,
            float a12,
            float a13,
            float a14,
            float a15,
            float a16,
            float a17,
            float a18,
            float a19) {
        return VehicleNatives.add6DofConstraint(
                a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18,
                a19);
    }

    public static int addRopeConstraint(
            int a0, int a1, float a2, float a3, float a4, float a5, float a6, float a7, float a8) {
        return VehicleNatives.addRopeConstraint(a0, a1, a2, a3, a4, a5, a6, a7, a8);
    }

    public static void setConstraintERP(int a0, float a1, int a2) {
        VehicleNatives.setConstraintERP(a0, a1, a2);
    }

    public static void removeConstraint(int a0) {
        VehicleNatives.removeConstraint(a0);
    }

    public static void clearPhysicsMeshes() {
        WorldNatives.clearPhysicsMeshes();
    }

    public static void definePhysicsMesh(int a0, boolean a1, float[] a2) {
        WorldNatives.definePhysicsMesh(a0, a1, a2);
    }

    public static void initializeRagdollPose(
            int a0, float[] a1, float a2, float a3, float a4, float a5) {
        RagdollNatives.initializeRagdollPose(a0, a1, a2, a3, a4, a5);
    }

    public static void initializeRagdollSkeleton(int a0, int[] a1) {
        RagdollNatives.initializeRagdollSkeleton(a0, a1);
    }

    public static void addRagdoll(
            int a0, float a1, float a2, float a3, float a4, float a5, float a6, float a7) {
        RagdollNatives.addRagdoll(a0, a1, a2, a3, a4, a5, a6, a7);
    }

    public static void removeRagdoll(int a0) {
        RagdollNatives.removeRagdoll(a0);
    }

    public static int simulateRagdoll(int a0, float[] a1) {
        return RagdollNatives.simulateRagdoll(a0, a1);
    }

    public static int simulateRagdollWithRigidBodyOutput(int a0, float[] a1, float[] a2) {
        return RagdollNatives.simulateRagdollWithRigidBodyOutput(a0, a1, a2);
    }

    public static int updateSkeletonFromNetworkPhysics(int a0, float[] a1, float[] a2) {
        return RagdollNatives.updateSkeletonFromNetworkPhysics(a0, a1, a2);
    }

    public static void getCorrectedWorldSpace(int a0, float[] a1) {
        WorldNatives.getCorrectedWorldSpace(a0, a1);
    }

    public static void setRagdollLocalTransformRotation(
            int a0, float a1, float a2, float a3, float a4) {
        RagdollNatives.setRagdollLocalTransformRotation(a0, a1, a2, a3, a4);
    }

    public static void updateRagdoll(
            int a0, float a1, float a2, float a3, float a4, float a5, float a6, float a7) {
        RagdollNatives.updateRagdoll(a0, a1, a2, a3, a4, a5, a6, a7);
    }

    public static void updateRagdollSkeletonTransforms(int a0, int a1, float[] a2) {
        RagdollNatives.updateRagdollSkeletonTransforms(a0, a1, a2);
    }

    public static void updateRagdollSkeletonPreviousTransforms(
            int a0, int a1, float a2, float[] a3) {
        RagdollNatives.updateRagdollSkeletonPreviousTransforms(a0, a1, a2, a3);
    }

    public static int getRagdollSimulationState(int a0) {
        return RagdollNatives.getRagdollSimulationState(a0);
    }

    public static void resetSkeletonPose(int a0) {
        RagdollNatives.resetSkeletonPose(a0);
    }

    public static void setRagdollActive(int a0, boolean a1) {
        RagdollNatives.setRagdollActive(a0, a1);
    }

    public static void drawDebugSingleBone(int a0, boolean a1) {
        RagdollNatives.drawDebugSingleBone(a0, a1);
    }

    public static void drawDebugRagdollSkeleton(int a0, boolean a1, boolean a2) {
        RagdollNatives.drawDebugRagdollSkeleton(a0, a1, a2);
    }

    public static void drawDebugRagdollBodyParts(int a0, boolean a1, boolean a2) {
        RagdollNatives.drawDebugRagdollBodyParts(a0, a1, a2);
    }

    public static void highlightRagdollBodyPart(int a0, int a1) {
        RagdollNatives.highlightRagdollBodyPart(a0, a1);
    }

    public static void applyForce(int a0, int a1, float[] a2) {
        RagdollNatives.applyForce(a0, a1, a2);
    }

    public static void applyImpulse(int a0, int a1, float[] a2) {
        RagdollNatives.applyImpulse(a0, a1, a2);
    }

    public static void detachConstraint(int a0, int a1) {
        VehicleNatives.detachConstraint(a0, a1);
    }

    public static void updateBallistics(int a0, float a1, float a2, float a3) {
        BallisticsNatives.updateBallistics(a0, a1, a2, a3);
    }

    public static void updateBallisticsMuzzleAimDirection(int a0, float a1, float a2, float a3) {
        BallisticsNatives.updateBallisticsMuzzleAimDirection(a0, a1, a2, a3);
    }

    public static void setBallisticsSize(int a0, float a1) {
        BallisticsNatives.setBallisticsSize(a0, a1);
    }

    public static void setBallisticsColor(int a0, float a1, float a2, float a3) {
        BallisticsNatives.setBallisticsColor(a0, a1, a2, a3);
    }

    public static int getBallisticsTargets(int a0, float a1, int a2, float[] a3) {
        return BallisticsNatives.getBallisticsTargets(a0, a1, a2, a3);
    }

    public static int getBallisticsTargetsSpreadData(
            int a0, float a1, float a2, float a3, int a4, int a5, float[] a6) {
        return BallisticsNatives.getBallisticsTargetsSpreadData(a0, a1, a2, a3, a4, a5, a6);
    }

    public static int getBallisticsCameraTargets(int a0, float a1, int a2, boolean a3, float[] a4) {
        return BallisticsNatives.getBallisticsCameraTargets(a0, a1, a2, a3, a4);
    }

    public static void setBallisticsRange(int a0, float a1) {
        BallisticsNatives.setBallisticsRange(a0, a1);
    }

    public static void removeBallistics(int a0) {
        BallisticsNatives.removeBallistics(a0);
    }

    public static void updateBallisticsAimReticlePosition(int a0, float a1, float a2, float a3) {
        BallisticsNatives.updateBallisticsAimReticlePosition(a0, a1, a2, a3);
    }

    public static void updateBallisticsAimReticleRotation(
            int a0, float a1, float a2, float a3, float a4) {
        BallisticsNatives.updateBallisticsAimReticleRotation(a0, a1, a2, a3, a4);
    }

    public static void updateBallisticsAimReticleQuaternion(
            int a0, float a1, float a2, float a3, float a4) {
        BallisticsNatives.updateBallisticsAimReticleQuaternion(a0, a1, a2, a3, a4);
    }

    public static void updateBallisticsAimReticleRotate(
            int a0, float a1, float a2, float a3, float a4) {
        BallisticsNatives.updateBallisticsAimReticleRotate(a0, a1, a2, a3, a4);
    }

    public static void updateBallisticsTargetSkeleton(int a0, int a1, float[] a2) {
        BallisticsNatives.updateBallisticsTargetSkeleton(a0, a1, a2);
    }

    public static void updateBallisticsTarget(
            int a0,
            float a1,
            float a2,
            float a3,
            float a4,
            float a5,
            float a6,
            float a7,
            boolean a8) {
        BallisticsNatives.updateBallisticsTarget(a0, a1, a2, a3, a4, a5, a6, a7, a8);
    }

    public static void setBallisticsTargetAxis(int a0, float a1, float a2, float a3) {
        BallisticsNatives.setBallisticsTargetAxis(a0, a1, a2, a3);
    }

    public static int addBallisticsTarget(int a0) {
        return BallisticsNatives.addBallisticsTarget(a0);
    }

    public static int removeBallisticsTarget(int a0) {
        return BallisticsNatives.removeBallisticsTarget(a0);
    }

    public static int getTargetedBodyPart(int a0) {
        return BallisticsNatives.getTargetedBodyPart(a0);
    }

    public static void setRagdollMass(float a0) {
        RagdollNatives.setRagdollMass(a0);
    }

    public static boolean checkWheelCollision(int a0, int a1, int a2) {
        return VehicleNatives.checkWheelCollision(a0, a1, a2);
    }

    public static boolean defineRagdollConstraints(float[] a0, boolean a1) {
        return RagdollNatives.defineRagdollConstraints(a0, a1);
    }

    public static boolean defineRagdollAnchors(float[] a0, boolean a1) {
        return RagdollNatives.defineRagdollAnchors(a0, a1);
    }

    public static boolean defineRagdollBodyPartInfo(float[] a0, boolean a1) {
        return RagdollNatives.defineRagdollBodyPartInfo(a0, a1);
    }

    public static boolean defineRagdollBodyDynamics(float[] a0, boolean a1) {
        return RagdollNatives.defineRagdollBodyDynamics(a0, a1);
    }

    public static boolean setRagdollBodyDynamics(int a0, float[] a1) {
        return RagdollNatives.setRagdollBodyDynamics(a0, a1);
    }

    public static boolean resetRagdollBodyDynamics(int a0) {
        return RagdollNatives.resetRagdollBodyDynamics(a0);
    }

    public static void setBallisticsTargetAdjustingShapeScale(float a0, float a1, float a2) {
        BallisticsNatives.setBallisticsTargetAdjustingShapeScale(a0, a1, a2);
    }

    public static void setBallisticsTargetAllPartsColor(float a0, float a1, float a2) {
        BallisticsNatives.setBallisticsTargetAllPartsColor(a0, a1, a2);
    }
}
