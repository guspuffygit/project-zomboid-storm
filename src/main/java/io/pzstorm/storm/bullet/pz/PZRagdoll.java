// Port of PZ glue PZRagdoll (PZRagdoll.cpp; @00165a60..@00168fb0).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionWorld;
import io.pzstorm.storm.bullet.dynamics.btDiscreteDynamicsWorld;
import io.pzstorm.storm.bullet.dynamics.btDynamicsWorld;
import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.ArrayList;
import java.util.List;

/**
 * 0xe8 bytes: +8 bool m_drawSingleBoneFlag (JNI drawDebugSingleBone), +9 bool
 * m_drawOnlyHighlightedBodyPart, +0xa bool m_drawSkeleton, +0xb bool m_drawSingleBone, +0xc bool
 * m_drawBodyParts, +0xd bool m_drawHighlightedBodyParts, +0x10 bool m_drawConstraints, +0x14 int
 * m_singleBoneIndex, +0x18 int, +0x1c int m_highlightedBodyPart (1), +0x20 Ragdoll*, +0x28
 * SkeletonPose*, +0x30 btTransform m_localTransform, +0xb0 int id, +0xb8 double mass (70.0), +0xc0
 * bool active, +0xc8 btVector3 position.
 */
public class PZRagdoll {

    static final String LOC = "/usr/src/pz/pzbullet/PZRagdoll.cpp:";

    /** +8 (set by JNI drawDebugSingleBone). */
    public boolean m_debugSingleBone;

    /** +9 */
    public boolean m_drawOnlyHighlightedBodyPart;

    /** +0xa */
    public boolean m_drawSkeleton;

    /** +0xb */
    public boolean m_drawSingleBone;

    /** +0xc */
    public boolean m_drawBodyParts;

    /** +0xd */
    public boolean m_drawHighlightedBodyParts;

    /** +0x10 */
    public boolean m_drawConstraints;

    /** +0x14 */
    public int m_singleBoneIndex;

    /** +0x18 */
    public int m_unknown18;

    /** +0x1c */
    public int m_highlightedBodyPart = 1;

    /** +0x20 */
    public Ragdoll m_ragdoll;

    /** +0x28 */
    public SkeletonPose m_skeletonPose;

    /** +0x30 */
    public final btTransform m_localTransform = new btTransform();

    /** +0xb0 */
    public int m_id;

    /** +0xb8 */
    public double m_mass = 70.0;

    /** +0xc0 */
    public boolean m_active;

    /** +0xc8 */
    public final btVector3 m_position = new btVector3();

    private static void log(String line, String msg) {
        PZDebugLog.instance().log("PZBullet", PZDebugType.Debug, LOC + line, msg);
    }

    /**
     * @00168320
     */
    public PZRagdoll(int id) {
        m_localTransform.set(btTransform.getIdentity());
        m_id = id;
        m_active = false;
        m_position.set(PZbtVector3.btVector3Zero);
        m_skeletonPose = createSkeletonPose();
        createRagdoll();
    }

    /** ~PZRagdoll @00165a60 */
    public void destroy() {
        if (m_ragdoll != null) {
            m_ragdoll.destroy();
        }
        m_ragdoll = null;
        m_skeletonPose = null;
    }

    public int getId() {
        return m_id;
    }

    /**
     * @00165b20: local transform = (rotation(x, y, z, w), origin 0).
     */
    public void setLocalTransformRotation(float x, float y, float z, float w) {
        m_localTransform.m_origin.setValue(0.0, 0.0, 0.0);
        m_localTransform.m_basis.setRotation(new btQuaternion(x, y, z, w));
    }

    /**
     * @00165c80
     */
    public void createRagdoll() {
        if (m_skeletonPose != null) {
            m_ragdoll = new Ragdoll();
            m_ragdoll.create(m_skeletonPose);
            return;
        }
        log("46", "[Failure] PZRagdoll::createRagdoll - skeletonPose == nullptr");
    }

    /**
     * @00165d00 (the transform is unused).
     */
    public void addToWorld(btTransform t) {
        log("62", "PZRagdoll::addToWorld");
        if (btGlobals.gDynamicsWorld != null) {
            log("69", "[Success] PZRagdoll::addToWorld");
            return;
        }
        log("65", "[Failure] PZRagdoll::addToWorld gDynamicsWorld == nullptr");
    }

    /**
     * @00165d90
     */
    public void reset(int id) {
        m_id = id;
        m_ragdoll.m_transform.set(btTransform.getIdentity());
        m_active = false;
        m_localTransform.set(btTransform.getIdentity());
        m_position.set(PZbtVector3.btVector3Zero);
    }

    /**
     * @00166050
     */
    public void resetSkeletonPose() {
        m_skeletonPose.resetBoneTransforms();
        m_skeletonPose.update();
        m_ragdoll.updateBodiesFromSkeleton(m_skeletonPose);
    }

    /**
     * @00166080
     */
    public void setActive(boolean active) {
        if (m_active == active) {
            return;
        }
        if (!active) {
            m_ragdoll.removeFromSimulation();
            m_active = active;
            return;
        }
        m_ragdoll.addToSimulation((btDynamicsWorld) btGlobals.gDynamicsWorld);
        m_active = active;
    }

    /**
     * @001660e0
     */
    public void removeFromWorld() {
        setActive(false);
    }

    /**
     * @001660f0: writes 7 floats (x, y, z, qx, qy, qz, qw) at idx[0]; idx[0] += 7.
     */
    public static void SetBoneTransformElements(
            float[] out, int[] idx, btVector3 v, btQuaternion q) {
        int i = idx[0];
        out[i] = (float) v.x();
        out[i + 1] = (float) v.y();
        out[i + 2] = (float) v.z();
        out[i + 3] = (float) q.x();
        out[i + 4] = (float) q.y();
        out[i + 5] = (float) q.z();
        idx[0] = i + 7;
        out[i + 6] = (float) q.w();
    }

    /**
     * @00166170
     */
    public int simulate(float[] skeletonElements) {
        int n = m_skeletonPose.m_skeleton.m_boneCount;
        PZDebugLog.instance()
                .log(
                        "PZBullet",
                        PZDebugType.Trace,
                        LOC + "184",
                        "PZRagdoll::simulate(skeletonElements)");
        int[] idx = {0};
        m_ragdoll.updateSkeletonFromBodies(m_skeletonPose);

        // Root local transform: keep its rotation (matrix -> quaternion -> matrix), zero origin.
        BoneTransform local0 = m_skeletonPose.m_localTransforms.get(0);
        btQuaternion q0 = local0.m_basis.getRotation();
        local0.m_basis.setRotation(q0);
        local0.m_origin.set(PZbtVector3.btVector3Zero);

        // Bone type 1 keeps its absolute rotation at the model origin.
        int bone1 = SkeletonBone.GetBoneIndex(1);
        btTransform t1 = m_skeletonPose.getBonePoseAbsolute(bone1);
        t1.m_origin.set(PZbtVector3.btVector3Zero);
        m_skeletonPose.setBonePoseAbsolute(bone1, t1);

        // Bone type 0x22 takes bone type 2's absolute pose.
        btTransform t2 = m_skeletonPose.getBonePoseAbsolute(SkeletonBone.GetBoneIndex(2));
        m_skeletonPose.setBonePoseAbsolute(SkeletonBone.GetBoneIndex(0x22), t2);

        // Bone 0: bindPoseRelative[0] * local[0] * m_localTransform^-1 (not stored back).
        btTransform root =
                m_skeletonPose
                        .m_skeleton
                        .m_bindPoseRelative
                        .get(0)
                        .mul(m_skeletonPose.m_localTransforms.get(0))
                        .mul(m_localTransform.inverse());
        root.m_origin.w = 0.0;
        SetBoneTransformElements(skeletonElements, idx, root.m_origin, root.getRotation());

        for (int i = 1; i < n; i++) {
            btTransform rel = m_skeletonPose.getBonePoseRelative(i);
            SetBoneTransformElements(skeletonElements, idx, rel.m_origin, rel.getRotation());
        }
        return n;
    }

    /**
     * @001675b0
     */
    public int simulate(float[] skeletonElements, float[] rigidBodyElements) {
        PZDebugLog.instance()
                .log(
                        "PZBullet",
                        PZDebugType.Trace,
                        LOC + "248",
                        "PZRagdoll::simulate(skeletonElements,rigidBodyElements)");
        int r = simulate(skeletonElements);
        m_ragdoll.getRigidBodyData(rigidBodyElements);
        m_ragdoll.clampVelocity();
        return r;
    }

    /**
     * @00167620
     */
    public double getBoneLength(int bone) {
        BonePair pair = RagdollBuilder.Instance().m_bonePairs.get(bone);
        btVector3 a = m_skeletonPose.m_localTransforms.get(pair.first).m_origin;
        btVector3 b = m_skeletonPose.m_localTransforms.get(pair.second).m_origin;
        double dy = a.y - b.y;
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dy * dy + dx * dx + dz * dz);
    }

    /**
     * @001677b0: ragdoll transform = (rotation(qx, qy, qz, qw), origin (x, y, z)).
     */
    public void update(float x, float y, float z, float qx, float qy, float qz, float qw) {
        m_position.x = (double) x;
        m_position.y = (double) y;
        m_position.z = (double) z;
        m_position.w = 0.0;
        m_ragdoll.m_transform.m_origin.set(m_position);
        m_ragdoll.m_transform.m_basis.setRotation(
                new btQuaternion((double) qx, (double) qy, (double) qz, (double) qw));
    }

    /**
     * @00167930
     */
    public int getSimulationState() {
        int state = 0;
        for (int i = 0; i < Ragdoll.BODY_PART_COUNT; i++) {
            btRigidBody body = m_ragdoll.getRigidBody(i);
            if (state == 0) {
                state = body.m_activationState1;
            } else if (body.m_activationState1 != state) {
                return 0;
            }
        }
        return state;
    }

    /**
     * @001679c0
     */
    public void applyForce(int part, btVector3 force, btVector3 relPos) {
        m_ragdoll.applyForce(part, force, relPos);
    }

    /**
     * @001679d0
     */
    public void applyImpulse(int part, btVector3 impulse, btVector3 relPos) {
        m_ragdoll.applyImpulse(part, impulse, relPos);
    }

    /**
     * @001679e0 (the w components C++ passes are uninitialised stack; 0 here).
     */
    public void applyForce(int part, float[] f) {
        btVector3 force = new btVector3((double) f[0], (double) f[1], (double) f[2]);
        btVector3 relPos = new btVector3((double) f[3], (double) f[4], (double) f[5]);
        m_ragdoll.applyForce(part, force, relPos);
    }

    /**
     * @00167a70
     */
    public void applyImpulse(int part, float[] f) {
        btVector3 impulse = new btVector3((double) f[0], (double) f[1], (double) f[2]);
        btVector3 relPos = new btVector3((double) f[3], (double) f[4], (double) f[5]);
        m_ragdoll.applyImpulse(part, impulse, relPos);
    }

    /**
     * @00167b00
     */
    public void detachConstraint(int joint) {
        m_ragdoll.detachConstraint(joint);
    }

    /**
     * @00167b10
     */
    public void setMass(float mass) {
        double m = (double) mass;
        if (m != m_mass) {
            for (int i = 0; i < Ragdoll.BODY_PART_COUNT; i++) {
                btRigidBody body = m_ragdoll.getRigidBody(i);
                btVector3 inertia = new btVector3();
                body.m_collisionShape.calculateLocalInertia(m, inertia);
                m_ragdoll.getRigidBody(i).setMassProps(m, inertia);
            }
            m_mass = m;
        }
    }

    /**
     * @00167bb0
     */
    public void debugRenderBodyParts(btCollisionWorld world, btVector3 color, boolean byState) {
        for (int i = 0; i < Ragdoll.BODY_PART_COUNT; i++) {
            if (!m_drawOnlyHighlightedBodyPart || m_highlightedBodyPart == i) {
                btRigidBody body = m_ragdoll.getRigidBody(i);
                btTransform t = new btTransform(body.m_worldTransform);
                btVector3 c = new btVector3(color);
                if (byState) {
                    switch (body.m_activationState1) {
                        case 1:
                            c.set(PZbtVector3.White);
                            break;
                        case 2:
                            c.set(PZbtVector3.Blue);
                            break;
                        case 3:
                            c.set(PZbtVector3.Cyan);
                            break;
                        case 5:
                            c.set(PZbtVector3.Purple);
                            break;
                        default:
                            c.set(PZbtVector3.Red);
                            break;
                    }
                }
                world.debugDrawObject(t, body.m_collisionShape, c);
            }
        }
    }

    /**
     * @00167d80
     */
    public void debugRenderHighlightedBodyParts(btCollisionWorld world) {
        for (int i = 0; i < Ragdoll.BODY_PART_COUNT; i++) {
            btRigidBody body = m_ragdoll.getRigidBody(i);
            if (body != null) {
                BulletObject bo = (BulletObject) body.m_userObjectPointer;
                if (bo != null && bo.flag40) {
                    world.debugDrawObject(body.m_worldTransform, body.m_collisionShape, bo.color);
                    bo.flag40 = false;
                }
            }
        }
    }

    /**
     * @00167df0
     */
    public void debugRenderSkeleton(btCollisionWorld world, btVector3 color) {
        Skeleton skeleton = m_skeletonPose.m_skeleton;
        int n = skeleton.m_boneCount;
        for (int bone = 0; bone < n; bone++) {
            if ((!m_drawSingleBone || m_singleBoneIndex == bone)
                    && skeleton.m_bones.get(bone).parent > -1) {
                btVector3 pos = m_ragdoll.m_transform.m_origin;
                btVector3 a = m_skeletonPose.getBonePoseAbsolute(bone).m_origin;
                btVector3 from = new btVector3(pos.x + a.x, pos.y + a.y, pos.z + a.z);
                from.w = 0.0;
                pos = m_ragdoll.m_transform.m_origin;
                btVector3 b =
                        m_skeletonPose.getBonePoseAbsolute(skeleton.m_bones.get(bone).parent)
                                .m_origin;
                btVector3 to = new btVector3(pos.x + b.x, pos.y + b.y, pos.z + b.z);
                to.w = 0.0;
                // vtable +0x168 = PZDiscreteDynamicsWorld::debugDrawLine @00187180, which
                // tail-calls m_debugDrawer->drawLine(from, to, color).
                world.getDebugDrawer().drawLine(from, to, color);
            }
        }
    }

    /**
     * @00167f30
     */
    public void debugRenderConstraints(btCollisionWorld world) {
        for (int i = 0; i < Ragdoll.JOINT_COUNT; i++) {
            ((btDiscreteDynamicsWorld) world).debugDrawConstraint(m_ragdoll.getJointConstraint(i));
        }
    }

    /**
     * @00167f70
     */
    public void debugDraw(btCollisionWorld world) {
        if (m_drawBodyParts) {
            debugRenderBodyParts(world, PZbtVector3.White, true);
        }
        if (m_drawHighlightedBodyParts) {
            debugRenderHighlightedBodyParts(world);
        }
        if (m_drawSkeleton) {
            debugRenderSkeleton(world, PZbtVector3.Orange);
        }
        if (m_drawConstraints) {
            debugRenderConstraints(world);
        }
    }

    /**
     * @00168000
     */
    public void render() {
        debugDraw((btCollisionWorld) btGlobals.gDynamicsWorld);
    }

    /**
     * @00168010
     */
    public void updateConstraints() {
        m_ragdoll.updateConstraints();
    }

    /**
     * @00168020
     */
    public void updateAnchors() {
        if (m_skeletonPose != null) {
            m_ragdoll.updateAnchors(m_skeletonPose);
        }
    }

    /**
     * @00168040
     */
    public void updateBodyParts() {
        m_ragdoll.updateBodyParts(m_skeletonPose);
    }

    /**
     * @00168050
     */
    public void updateBodyDynamics(RagdollBodyDynamics dynamics) {
        m_ragdoll.updateBodyDynamics(dynamics);
    }

    /**
     * @00168060
     */
    public void resetBodyDynamics() {
        m_ragdoll.resetBodyDynamics();
    }

    /**
     * @00168070 (the body loop has no effect; rigidBodyElements is unused).
     */
    public int updateRigidBodies(float[] rigidBodyElements, float[] skeletonElements) {
        for (int i = 0; i < Ragdoll.BODY_PART_COUNT; i++) {
            m_ragdoll.getRigidBody(i);
        }
        simulate(skeletonElements);
        return Ragdoll.BODY_PART_COUNT;
    }

    /**
     * @001680b0
     */
    public static SkeletonPose createSkeletonPose() {
        RagdollBuilder b = RagdollBuilder.Instance();
        if (b.m_defaultSkeleton == null) {
            log(
                    "34",
                    "[Failure] PZRagdoll::createSkeletonPose - RagdollBuilder::defaultSkeleton =="
                            + " nullptr");
            return null;
        }
        log("38", "createSkeletonPose success.");
        return new SkeletonPose(RagdollBuilder.Instance().m_defaultSkeleton);
    }

    private static void resize(List<btTransform> v, int n) {
        while (v.size() > n) {
            v.remove(v.size() - 1);
        }
        while (v.size() < n) {
            v.add(new btTransform(btTransform.getIdentity()));
        }
    }

    /**
     * @00168510
     */
    public void parseBoneTransforms(
            int count,
            float[] data,
            List<btTransform> transforms,
            List<btTransform> previousTransforms) {
        // vector::resize(count) on both (default-appended entries are identity).
        resize(transforms, count);
        resize(previousTransforms, count);
        int b = 0;
        for (int i = 0; i < count; i++) {
            btTransform t = transforms.get(i);
            t.m_origin.x = (double) data[b];
            t.m_origin.y = (double) data[b + 1];
            t.m_origin.z = (double) data[b + 2];
            t.m_origin.w = 0.0;
            t.m_basis.setRotation(
                    new btQuaternion(
                            (double) data[b + 3],
                            (double) data[b + 4],
                            (double) data[b + 5],
                            (double) data[b + 6]));
            b += 7;
        }
        // local[0] *= m_localTransform (count == 0 dereferences an empty vector in C++).
        btTransform local0 = transforms.get(0);
        local0.set(local0.mul(m_localTransform));
        previousTransforms.get(0).set(local0);
        List<Skeleton.Bone> bones = m_skeletonPose.m_skeleton.m_bones;
        for (int i = 1; i < count; i++) {
            int parent = bones.get(i).parent;
            previousTransforms.get(i).set(previousTransforms.get(parent).mul(transforms.get(i)));
        }
    }

    /**
     * @00168ce0
     */
    public void updateSkeletonBoneTransforms(int count, float[] data) {
        PZDebugLog.instance()
                .logf(
                        "PZBullet",
                        PZDebugType.Trace,
                        LOC + "120",
                        "PZRagdoll::updateSkeletonBoneTransforms(in_numberOfBones=%d, bones)",
                        count);
        List<btTransform> local = new ArrayList<>();
        List<btTransform> absolute = new ArrayList<>();
        parseBoneTransforms(count, data, local, absolute);
        List<btTransform> copy = new ArrayList<>(absolute.size());
        for (btTransform t : absolute) {
            copy.add(new btTransform(t));
        }
        m_skeletonPose.setSkeletonPose(copy);
        m_ragdoll.updateBodiesFromSkeleton(m_skeletonPose);
        PZDebugLog log = PZDebugLog.instance();
        double len = getBoneLength(8);
        log.logf(
                "PZBullet",
                PZDebugType.Trace,
                LOC + "129",
                "PZRagdoll::updateSkeletonBoneTransforms"
                        + " getBoneLength(BODYPART_LEFT_LOWER_ARM): %f",
                len);
    }

    /**
     * @00168ee0
     */
    public void updateRagdollSkeletonPreviousBoneTransforms(int count, float dt, float[] data) {
        PZDebugLog.instance()
                .logf(
                        "PZBullet",
                        PZDebugType.Trace,
                        LOC + "134",
                        "PZRagdoll::updateRagdollSkeletonPreviousBoneTransforms(in_numberOfBones=%d,"
                                + " bones)",
                        count);
        List<btTransform> local = new ArrayList<>();
        List<btTransform> absolute = new ArrayList<>();
        parseBoneTransforms(count, data, local, absolute);
        m_ragdoll.applySkeletonVelocities(m_skeletonPose, dt, absolute);
    }
}
