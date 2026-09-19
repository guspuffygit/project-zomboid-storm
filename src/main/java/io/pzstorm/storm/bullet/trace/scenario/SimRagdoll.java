package io.pzstorm.storm.bullet.trace.scenario;

import io.pzstorm.storm.bullet.trace.BulletBackend;

/**
 * The library-facing half of a game {@code RagdollController}: a falling character (zombie or
 * player) at world square (x, y, level z), facing {@code forward} radians.
 */
public final class SimRagdoll {

    private final ScenarioContext ctx;
    private final BulletBackend b;
    public final int id;
    public float x;
    public float y;
    public float z;
    public float forward;

    public boolean addedToWorld;
    public boolean active;
    public boolean contactingVehicle;
    public int simulationState;
    public int lastBoneCount;
    private float animT;

    /** Shared per class in the game ({@code static final} buffers). */
    public final float[] skeletonBuffer = new float[RagdollData.SKELETON_FLOATS];

    public final float[] rigidBodyBuffer = new float[RagdollData.RIGID_BODY_FLOATS];

    public SimRagdoll(ScenarioContext ctx, int id, float x, float y, float z, float forward) {
        this.ctx = ctx;
        this.b = ctx.bullet;
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.forward = forward;
    }

    /** {@code RagdollController.pzSpaceToBulletSpace} of the character position. */
    public float[] bulletPos() {
        return new float[] {x, z * 2.44949F, y};
    }

    /** {@code updateRagdollWorldTransform}: {@code Bullet.updateRagdoll(id, pos, rot)}. */
    public void updateWorldTransform() {
        float[] p = bulletPos();
        float[] q = RagdollData.worldRotation(forward);
        b.updateRagdoll(id, p[0], p[1], p[2], q[0], q[1], q[2], q[3]);
    }

    /** {@code setRagdollLocalRotation}. */
    public void setLocalRotation() {
        float[] q = RagdollData.localRotation();
        b.setRagdollLocalTransformRotation(id, q[0], q[1], q[2], q[3]);
    }

    /**
     * {@code RagdollController.initialize}: {@code addToWorld}, {@code updateRagdollSkeleton}
     * (local rotation, world transform, animation bones, bone velocities) and {@code
     * setActive(true)}.
     */
    public void initialize(float animDeltaT) {
        if (!addedToWorld) {
            float[] p = bulletPos();
            float[] q = RagdollData.worldRotation(forward);
            b.addRagdoll(id, p[0], p[1], p[2], q[0], q[1], q[2], q[3]);
            addedToWorld = true;
        }
        setLocalRotation();
        updateWorldTransform();
        float[] prev = RagdollData.animatedPose(animT, 0.4F);
        animT += Math.max(animDeltaT, 0) * 6.0F;
        float[] cur = RagdollData.animatedPose(animT, 0.4F);
        b.updateRagdollSkeletonTransforms(id, RagdollData.BONES, cur);
        b.updateRagdollSkeletonPreviousTransforms(
                id, RagdollData.BONES, animDeltaT, RagdollData.velocities(prev, cur, animDeltaT));
        setActive(true);
    }

    /** {@code RagdollController.setActive}. */
    public void setActive(boolean a) {
        updateWorldTransform();
        b.setRagdollActive(id, a);
        active = a;
    }

    /**
     * {@code RagdollController.update} after initialisation: {@code simulateRagdoll} (world
     * transform, local rotation, {@code simulateRagdollWithRigidBodyOutput}) then the state query.
     */
    public void update() {
        updateWorldTransform();
        setLocalRotation();
        lastBoneCount = b.simulateRagdollWithRigidBodyOutput(id, skeletonBuffer, rigidBodyBuffer);
        simulationState = b.getRagdollSimulationState(id);
    }

    /**
     * {@code simulateHitReaction}: a shot/explosion from direction (dx, dy) (normalised in the iso
     * plane) hits {@code bodyPart}.
     */
    public void hit(int bodyPart, float dx, float dy, float impulse, float upImpulse) {
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 0) {
            dx /= len;
            dy /= len;
        }
        float[] buf = {dx * impulse, upImpulse, dy * impulse, 0.0F, 0.0F, 0.0F};
        b.applyImpulse(id, bodyPart, buf);
    }

    /** {@code vehicleCollision}: contact begins / ends. */
    public void vehicleContact(boolean contacting) {
        if (contactingVehicle && !contacting) {
            b.resetRagdollBodyDynamics(id);
        } else if (!contactingVehicle && contacting) {
            b.setRagdollBodyDynamics(id, RagdollData.vehicleBodyDynamics());
        }
        contactingVehicle = contacting;
    }

    /**
     * Where the pelvis ended up: the first rigid body of the last output (7 floats per body part,
     * position first), if the library wrote one.
     */
    public float[] pelvis() {
        return new float[] {rigidBodyBuffer[0], rigidBodyBuffer[1], rigidBodyBuffer[2]};
    }

    /** {@code removeFromWorld}. */
    public void remove() {
        if (addedToWorld) {
            b.removeRagdoll(id);
            addedToWorld = false;
        }
    }
}
