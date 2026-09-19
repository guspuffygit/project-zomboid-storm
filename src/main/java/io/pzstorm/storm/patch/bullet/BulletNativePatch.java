package io.pzstorm.storm.patch.bullet;

import io.pzstorm.storm.bullet.StormBullet;
import io.pzstorm.storm.patch.popman.NativeFacadePatch;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces the entire {@code PZBullet} JNI surface of {@code zombie.core.physics.Bullet} with the
 * pure-Java port in {@link StormBullet}, and stubs the {@code loadLibrary} call out of the static
 * {@code init()} so the library is never mapped. Overloaded natives share a name; delegation picks
 * the facade overload with the matching signature.
 */
public class BulletNativePatch extends NativeFacadePatch {

    static final String[] NATIVES = {
        "ToBullet",
        "getPZBulletVersion",
        "initPZBullet",
        "isWorldInit",
        "initWorld",
        "destroyWorld",
        "activateChunkMap",
        "deactivateChunkMap",
        "scrollChunkMap",
        "setChunkMinMaxLevel",
        "addVehicle",
        "removeVehicle",
        "controlVehicle",
        "setVehicleActive",
        "applyCentralForceToVehicle",
        "applyTorqueToVehicle",
        "teleportVehicle",
        "setTireInflation",
        "setTireRemoved",
        "stepSimulation",
        "getVehicleCount",
        "getVehiclePhysics",
        "getOwnVehiclePhysics",
        "setOwnVehiclePhysics",
        "setVehicleParams",
        "setVehicleMass",
        "getObjectPhysics",
        "createServerCell",
        "removeServerCell",
        "addPhysicsObject",
        "defineVehicleScript",
        "defineVehiclePhysicsMesh",
        "setVehicleVelocityMultiplier",
        "setVehicleStatic",
        "addHingeConstraint",
        "addPointConstraint",
        "add6DofConstraint",
        "addRopeConstraint",
        "setConstraintERP",
        "removeConstraint",
        "clearPhysicsMeshes",
        "definePhysicsMesh",
        "initializeRagdollPose",
        "initializeRagdollSkeleton",
        "addRagdoll",
        "removeRagdoll",
        "simulateRagdoll",
        "simulateRagdollWithRigidBodyOutput",
        "updateSkeletonFromNetworkPhysics",
        "getCorrectedWorldSpace",
        "setRagdollLocalTransformRotation",
        "updateRagdoll",
        "updateRagdollSkeletonTransforms",
        "updateRagdollSkeletonPreviousTransforms",
        "getRagdollSimulationState",
        "resetSkeletonPose",
        "setRagdollActive",
        "drawDebugSingleBone",
        "drawDebugRagdollSkeleton",
        "drawDebugRagdollBodyParts",
        "highlightRagdollBodyPart",
        "applyForce",
        "applyImpulse",
        "detachConstraint",
        "updateBallistics",
        "updateBallisticsMuzzleAimDirection",
        "setBallisticsSize",
        "setBallisticsColor",
        "getBallisticsTargets",
        "getBallisticsTargetsSpreadData",
        "getBallisticsCameraTargets",
        "setBallisticsRange",
        "removeBallistics",
        "updateBallisticsAimReticlePosition",
        "updateBallisticsAimReticleRotation",
        "updateBallisticsAimReticleQuaternion",
        "updateBallisticsAimReticleRotate",
        "updateBallisticsTargetSkeleton",
        "updateBallisticsTarget",
        "setBallisticsTargetAxis",
        "addBallisticsTarget",
        "removeBallisticsTarget",
        "getTargetedBodyPart",
        "setRagdollMass",
        "checkWheelCollision",
        "defineRagdollConstraints",
        "defineRagdollAnchors",
        "defineRagdollBodyPartInfo",
        "defineRagdollBodyDynamics",
        "setRagdollBodyDynamics",
        "resetRagdollBodyDynamics",
        "setBallisticsTargetAdjustingShapeScale",
        "setBallisticsTargetAllPartsColor",
    };

    public BulletNativePatch() {
        super("zombie.core.physics.Bullet", StormBullet.class, NATIVES);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return super.dynamicType(locator, typePool, builder)
                .visit(
                        MemberSubstitution.relaxed()
                                .method(ElementMatchers.named("loadLibrary"))
                                .stub()
                                .on(ElementMatchers.named("init").and(ElementMatchers.isStatic())));
    }
}
