// Port of PZ glue Ragdoll (Ragdoll.cpp; @0015df40..@00163b10).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.dynamics.btDynamicsWorld;
import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btConeTwistConstraint;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btHingeConstraint;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btTypedConstraint;
import io.pzstorm.storm.bullet.linearmath.btDefaultMotionState;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.ArrayList;
import java.util.List;

/**
 * 0x108 bytes: +0 bool useConstraints, +8 btTransform (root transform; origin at +0x68 is the
 * ragdoll position), +0x88 double constraint breaking threshold ((double)0.2f), +0x90 double 10.0,
 * +0x98 btDynamicsWorld*, +0xa0 vector&lt;int&gt;, +0xb8 vector&lt;btRigidBody*&gt; (11 bodies),
 * +0xd0 vector&lt;btTransform&gt; bone offsets, +0xe8 vector&lt;btTypedConstraint*&gt; (10 joints),
 * +0x100 last created btDefaultMotionState*.
 */
public class Ragdoll {

    static final String LOC = "/usr/src/pz/pzbullet/Ragdoll.cpp:";

    public static final int BODY_PART_COUNT = 11;
    public static final int JOINT_COUNT = 10;

    /** rigidBodyToBone (.rodata): body part index -&gt; skeleton bone index. */
    public static final int[] rigidBodyToBone = {2, 4, 6, 20, 21, 24, 25, 8, 9, 14, 15};

    /** +0 */
    public boolean m_useConstraints = true;

    /** +8 */
    public final btTransform m_transform = new btTransform();

    /** +0x68: origin of {@link #m_transform} (same storage). */
    public final btVector3 m_position = m_transform.m_origin;

    /** +0x88: loadConstraint copies it into each constraint's m_dbgDrawSize (+0x40). */
    public double m_constraintDebugDrawSize = (double) 0.2f;

    /** +0x90 */
    public double m_maxVelocity = 10.0;

    /** +0x98 */
    public btDynamicsWorld m_world;

    /** +0xa0 */
    public final ArrayList<Integer> m_boneToBodyPart = new ArrayList<>();

    /** +0xb8 */
    public final ArrayList<btRigidBody> m_bodies = new ArrayList<>();

    /** +0xd0 per-bone offset transforms (indexed by bone). */
    public final ArrayList<btTransform> m_boneOffsets = new ArrayList<>();

    /** +0xe8 */
    public final ArrayList<btTypedConstraint> m_constraints = new ArrayList<>();

    /** +0x100 */
    public btDefaultMotionState m_motionState;

    /**
     * @0015df40
     */
    public Ragdoll() {
        m_transform.setIdentity();
    }

    /**
     * @0015e0f0
     */
    public void deleteConstraints() {
        for (btTypedConstraint c : m_constraints) {
            if (c == null) {
                continue;
            }
            if (m_world != null) {
                m_world.removeConstraint(c);
            }
            c.destroy();
        }
    }

    /**
     * @0015e170
     */
    public void deleteRigidBodies() {
        for (btRigidBody body : m_bodies) {
            if (body == null) {
                continue;
            }
            if (m_world != null) {
                m_world.removeRigidBody(body);
            }
            body.m_userObjectPointer = null;
        }
    }

    /** ~Ragdoll @0015e2b0 */
    public void destroy() {
        deleteConstraints();
        deleteRigidBodies();
        m_world = null;
    }

    /**
     * @0015e320
     */
    public void addToSimulation(btDynamicsWorld world) {
        m_world = world;
        for (btRigidBody body : m_bodies) {
            body.m_updateRevision += 2;
            body.m_linearVelocity.set(PZbtVector3.btVector3Zero);
            body.m_angularVelocity.set(PZbtVector3.btVector3Zero);
            body.activate(true);
            world.addRigidBody(body, (short) 8, (short) 7);
        }
        if (m_useConstraints) {
            for (btTypedConstraint c : m_constraints) {
                c.setEnabled(true);
                world.addConstraint(c, true);
            }
        }
    }

    /**
     * @0015e440
     */
    public void removeFromSimulation() {
        for (btRigidBody body : m_bodies) {
            m_world.removeRigidBody(body);
            body.activate(false);
            body.m_updateRevision += 2;
            body.m_linearVelocity.set(PZbtVector3.btVector3Zero);
            body.m_angularVelocity.set(PZbtVector3.btVector3Zero);
        }
        for (btTypedConstraint c : m_constraints) {
            m_world.removeConstraint(c);
            c.setEnabled(false);
        }
        m_world = null;
    }

    /**
     * @0015e540
     */
    public btRigidBody createRigidBody(double mass, btTransform transform, btCollisionShape shape) {
        btVector3 inertia = new btVector3(0.0, 0.0, 0.0);
        if (mass != 0.0) {
            shape.calculateLocalInertia(mass, inertia);
        }
        m_motionState = new btDefaultMotionState(transform, btTransform.getIdentity());
        btRigidBody.btRigidBodyConstructionInfo info =
                new btRigidBody.btRigidBodyConstructionInfo(mass, m_motionState, shape, inertia);
        return new btRigidBody(info);
    }

    /**
     * @0015e9c0 For each body: cur = (T*A)*O, prev = (T*P)*O with A the bone's absolute pose, P
     * {@code previous[bone]} and O the bone offset; delta = prev^-1 * cur. The body gets
     * applyCentralImpulse(delta.origin * (1/dt)) and applyTorqueImpulse(eulerYPR(delta.basis) *
     * (1/dt)).
     */
    public void applySkeletonVelocities(
            SkeletonPose pose, float dt, List<? extends btTransform> previous) {
        for (int i = 0; i < BODY_PART_COUNT; i++) {
            btRigidBody body = m_bodies.get(i);
            if (body == null) {
                continue;
            }
            int bone = rigidBodyToBone[i];
            btTransform offset = m_boneOffsets.get(bone);
            btTransform abs = pose.getBonePoseAbsolute(bone);
            btTransform cur = m_transform.mul(abs).mul(offset);
            btTransform prev = m_transform.mul(previous.get(bone)).mul(offset);
            btTransform delta = prev.inverse().mul(cur);
            double invDt = 1.0 / (double) dt;
            double[] ypr = new double[3];
            delta.m_basis.getEulerYPR(ypr);
            btVector3 lin = delta.m_origin.mul(invDt);
            btVector3 ang = new btVector3(ypr[0] * invDt, ypr[1] * invDt, ypr[2] * invDt);
            body.applyCentralImpulse(lin);
            body.applyTorqueImpulse(ang);
        }
    }

    /**
     * @0015fa90 Out of the world: set the transform (updateRevision += 3 for setWorldTransform,
     * setLinearVelocity, setAngularVelocity) and zero both velocities; in a world, remove the body
     * first and re-add it with group 8 / mask 7 afterwards.
     */
    public void setActiveBodyTransform(btRigidBody body, btTransform t) {
        if (m_world != null) {
            m_world.removeRigidBody(body);
        }
        body.m_worldTransform.set(t);
        body.m_updateRevision += 3;
        body.m_linearVelocity.set(PZbtVector3.btVector3Zero);
        body.m_angularVelocity.set(PZbtVector3.btVector3Zero);
        if (m_world != null) {
            m_world.addRigidBody(body, (short) 8, (short) 7);
        }
    }

    /**
     * @0015fbe0
     */
    public btTypedConstraint getJointConstraint(int joint) {
        return m_constraints.get(joint);
    }

    /**
     * @0015fbf0
     */
    public void detachConstraint(int joint) {
        getJointConstraint(joint).setEnabled(false);
    }

    /**
     * @0015fc10
     */
    public btRigidBody getRigidBody(int part) {
        return m_bodies.get(part);
    }

    /**
     * Stack slot 0xb8 of anchorBoneToBodyPart: the quaternion y component of the previous call that
     * took the non-zero-direction path. The zero-direction path reads it uninitialised (the
     * compiler folded the other three components to 0). Emulated here; its value before the first
     * such call is unknown in the binary and taken as 0.0.
     */
    static double s_anchorStaleQuatY = 0.0;

    /**
     * @0015fc20 {@code a} = reverse, {@code b} = original.
     */
    public void anchorBoneToBodyPart(
            SkeletonPose pose, int bone, int part, boolean a, boolean b, btVector3 offset) {
        m_boneToBodyPart.set(bone, part);
        btTransform body = new btTransform(getRigidBody(part).m_worldTransform);
        BodyPartInfo info = RagdollBuilder.Instance().getRagdollBodyPartInfo(part);
        btTransform boneAbs = pose.getBonePoseAbsolute(bone);
        btTransform inv = boneAbs.inverse();
        if (b) {
            body.m_origin.addLocal(info.offset);
            m_boneOffsets.set(bone, inv.mul(body));
            return;
        }
        btTransform boneAbs2 = pose.getBonePoseAbsolute(bone);
        btVector3 center = body.m_origin.add(boneAbs2.m_origin).mul(0.5);
        btVector3 off = new btVector3(offset);
        off.addLocal(center);
        btTransform boneAbs3 = pose.getBonePoseAbsolute(bone);
        btVector3 dir = body.m_origin.sub(boneAbs3.m_origin);
        if (a) {
            dir = dir.negate();
        }
        btQuaternion q;
        if (dir.x == 0.0 && dir.y == 0.0 && dir.z == 0.0) {
            q = new btQuaternion(0.0, s_anchorStaleQuatY, 0.0, 0.0);
        } else {
            q = btQuaternion.shortestArcQuat(PZbtVector3.btVector3Up, dir.normalized());
            s_anchorStaleQuatY = q.y();
        }
        btTransform local = new btTransform(q, off);
        local.m_origin.w = 0.0;
        m_boneOffsets.set(bone, inv.mul(local));
    }

    /**
     * @001609f0 pose[bone] = (T^-1 * body) * offset[bone]^-1 for every bone anchored to a body.
     */
    public void updateSkeletonFromBodies(SkeletonPose pose) {
        btTransform tInv = m_transform.inverse();
        int n = pose.m_skeleton.m_boneCount;
        for (int bone = 0; bone < n; bone++) {
            int part = m_boneToBodyPart.get(bone);
            if (part < 0) {
                continue;
            }
            btRigidBody body = getRigidBody(part);
            if (body == null) {
                continue;
            }
            btTransform t = tInv.mul(body.m_worldTransform).mul(m_boneOffsets.get(bone).inverse());
            pose.setBonePoseAbsolute(bone, t);
        }
    }

    /**
     * @00161220 body[i] = (T * pose[bone]) * offset[bone], bone = rigidBodyToBone[i].
     */
    public void updateBodiesFromSkeleton(SkeletonPose pose) {
        for (int i = 0; i < BODY_PART_COUNT; i++) {
            btRigidBody body = getRigidBody(i);
            if (body == null) {
                continue;
            }
            int bone = rigidBodyToBone[i];
            btTransform offset = m_boneOffsets.get(bone);
            btTransform abs = pose.getBonePoseAbsolute(bone);
            setActiveBodyTransform(body, m_transform.mul(abs).mul(offset));
        }
    }

    /**
     * @00161780
     */
    public boolean loadConstraint(int joint) {
        RagdollConstraint c = RagdollBuilder.Instance().getRagdollConstraint(joint);
        btTransform frameA = new btTransform();
        frameA.m_basis.setEulerZYX(c.constraintAxisA.x, c.constraintAxisA.y, c.constraintAxisA.z);
        frameA.m_origin.set(c.constraintPositionOffsetA);
        btTransform frameB = new btTransform();
        frameB.m_basis.setEulerZYX(c.constraintAxisB.x, c.constraintAxisB.y, c.constraintAxisB.z);
        frameB.m_origin.set(c.constraintPositionOffsetB);
        btRigidBody rbA = getRigidBody(c.constraintPartA);
        btRigidBody rbB = getRigidBody(c.constraintPartB);
        btTypedConstraint con;
        if (c.constraintType == 4) {
            btHingeConstraint h = new btHingeConstraint(rbA, rbB, frameA, frameB, false);
            h.m_limit.set(
                    c.constraintLimit.x, c.constraintLimit.y, (double) 0.9f, (double) 0.3f, 1.0);
            con = h;
        } else if (c.constraintType == 5) {
            btConeTwistConstraint ct = new btConeTwistConstraint(rbA, rbB, frameA, frameB);
            ct.setLimit(
                    c.constraintLimit.x,
                    c.constraintLimit.y,
                    c.constraintLimit.z,
                    1.0,
                    (double) 0.3f,
                    1.0);
            con = ct;
        } else {
            logError(321, "[Failure] Ragdoll::loadConstraint - Unknown btTypedConstraintType");
            return false;
        }
        m_constraints.set(c.joint, con);
        con.setDbgDrawSize(m_constraintDebugDrawSize);
        return true;
    }

    /**
     * @00161cd0
     */
    public void applyForce(int part, btVector3 force, btVector3 relPos) {
        getRigidBody(part).applyForce(force, relPos);
    }

    /**
     * @00161dd0
     */
    public void applyImpulse(int part, btVector3 impulse, btVector3 relPos) {
        getRigidBody(part).applyImpulse(impulse, relPos);
    }

    /**
     * @00161f80 Rebuilds one body part: transform = (shortestArc(up, dir), midpoint of the bone
     * pair), dir = first.origin - second.origin (negated when second is first's parent; up when
     * exactly zero). The binary also computes the first bone's rotation quaternion and discards it
     * (dead code kept only for sqrt errno); not ported.
     */
    public void updateBodyPart(SkeletonPose pose, int part) {
        RagdollBuilder builder = RagdollBuilder.Instance();
        BodyPartInfo info = builder.getRagdollBodyPartInfo(part);
        btRigidBody body = getRigidBody(info.part);
        if (body != null && m_world != null) {
            m_world.removeRigidBody(body);
        }
        BonePair pair = builder.m_bonePairs.get(info.part);
        btVector3 a = new btVector3(pose.getBonePoseAbsolute(pair.second).m_origin);
        btVector3 b = new btVector3(pose.getBonePoseAbsolute(pair.first).m_origin);
        btVector3 center = new btVector3((a.x + b.x) * 0.5, (a.y + b.y) * 0.5, (a.z + b.z) * 0.5);
        center.w = 0.0;
        pose.getBonePoseAbsolute(pair.first); // dead getRotation() source
        btVector3 a2 = pose.getBonePoseAbsolute(pair.second).m_origin;
        btVector3 b2 = pose.getBonePoseAbsolute(pair.first).m_origin;
        double dx = b2.x - a2.x;
        double dy = b2.y - a2.y;
        double dz = b2.z - a2.z;
        if (pair.second == pose.m_skeleton.m_bones.get(pair.first).parent) {
            dx = -dx;
            dy = -dy;
            dz = -dz;
        }
        btVector3 dir;
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
            dir = PZbtVector3.btVector3Up;
        } else {
            dir = new btVector3(dx, dy, dz).normalized();
        }
        btQuaternion q = btQuaternion.shortestArcQuat(PZbtVector3.btVector3Up, dir);
        btTransform t = new btTransform(q, center);

        btCollisionShape shape = builder.getBodyPartCollisionShape(info.part);
        double mass = builder.getBodyPartMass(info.part);
        btVector3 inertia = new btVector3(0.0, 0.0, 0.0);
        if (mass != 0.0) {
            shape.calculateLocalInertia(mass, inertia);
        }
        if (body == null) {
            body = createRigidBody(mass, t, shape);
            m_bodies.set(info.part, body);
        } else {
            body.setCollisionShape(shape);
            body.setMassProps(mass, inertia);
            body.setWorldTransform(t);
        }
        builder.configureBodyDynamics(info.part, body);
        if (m_world != null) {
            m_world.getBroadphase()
                    .getOverlappingPairCache()
                    .cleanProxyFromPairs(body.getBroadphaseHandle(), m_world.m_dispatcher1);
            m_world.addRigidBody(body, (short) 8, (short) 7);
        }
    }

    /**
     * @00162c70
     */
    public void updateBodyParts(SkeletonPose pose) {
        for (int i = 0; i < BODY_PART_COUNT; i++) {
            updateBodyPart(pose, i);
        }
    }

    /**
     * @00162ca0
     */
    public void updateBodyDynamics(RagdollBodyDynamics dynamics) {
        for (int i = 0; i < BODY_PART_COUNT; i++) {
            btRigidBody body =
                    getRigidBody(RagdollBuilder.Instance().getRagdollBodyPartInfo(i).part);
            body.setDamping((double) dynamics.linearDamping, (double) dynamics.angularDamping);
            body.m_updateRevision += 2;
            body.m_deactivationTime = (double) dynamics.deactivationTime;
            body.m_angularSleepingThreshold = (double) dynamics.angularSleepingThreshold;
            body.m_friction = (double) dynamics.friction;
            body.m_linearSleepingThreshold = (double) dynamics.linearSleepingThreshold;
            body.m_rollingFriction = (double) dynamics.rollingFriction;
        }
    }

    /**
     * @00162dd0
     */
    public void resetBodyDynamics() {
        for (int i = 0; i < BODY_PART_COUNT; i++) {
            BodyPartInfo info = RagdollBuilder.Instance().getRagdollBodyPartInfo(i);
            btRigidBody body = getRigidBody(info.part);
            RagdollBuilder.Instance().configureBodyDynamics(info.part, body);
        }
    }

    /**
     * @00162ed0 7 floats per body: origin xyz, rotation xyzw.
     */
    public void getRigidBodyData(float[] out) {
        int o = 0;
        for (int i = 0; i < BODY_PART_COUNT; i++) {
            btRigidBody body = m_bodies.get(i);
            btTransform t = body.m_worldTransform;
            btQuaternion q = t.m_basis.getRotation();
            out[o] = (float) t.m_origin.x;
            out[o + 1] = (float) t.m_origin.y;
            out[o + 2] = (float) t.m_origin.z;
            out[o + 3] = (float) q.x();
            out[o + 4] = (float) q.y();
            out[o + 5] = (float) q.z();
            out[o + 6] = (float) q.w();
            o += 7;
        }
    }

    /**
     * @00163320
     */
    public void clampVelocity() {
        for (int i = 0; i < BODY_PART_COUNT; i++) {
            btRigidBody body = m_bodies.get(i);
            btVector3 v = body.m_linearVelocity;
            double len = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
            if (len > m_maxVelocity) {
                body.m_updateRevision += 1;
                double inv = 1.0 / len;
                double max = m_maxVelocity;
                v.x = v.x * inv * max;
                v.y = v.y * inv * max;
                v.z = inv * v.z * max;
                v.w = 0.0;
            }
        }
    }

    /**
     * @001634d0
     */
    public void loadConstraints() {
        logDebug(278, "Ragdoll::loadConstraints");
        while (m_constraints.size() < JOINT_COUNT) {
            m_constraints.add(null);
        }
        while (m_constraints.size() > JOINT_COUNT) {
            m_constraints.remove(m_constraints.size() - 1);
        }
        for (int j = 0; j < JOINT_COUNT; j++) {
            if (!loadConstraint(j)) {
                logError(284, "[Failure] Ragdoll::loadConstraints");
            }
        }
        logDebug(288, "[Success] Ragdoll::loadConstraints");
    }

    /**
     * @001635e0
     */
    public void updateConstraints() {
        deleteConstraints();
        loadConstraints();
    }

    /**
     * @00163600
     */
    public void loadAnchors(SkeletonPose pose) {
        int n = pose.m_skeleton.m_boneCount;
        resize(m_boneToBodyPart, n, -1);
        while (m_boneOffsets.size() < n) {
            m_boneOffsets.add(new btTransform());
        }
        while (m_boneOffsets.size() > n) {
            m_boneOffsets.remove(m_boneOffsets.size() - 1);
        }
        for (int i = 0; i < n; i++) {
            m_boneToBodyPart.set(i, -1);
            btTransform id = new btTransform();
            id.setIdentity();
            m_boneOffsets.set(i, id);
        }
        for (RagdollAnchor a : RagdollBuilder.Instance().getRagdollAnchors()) {
            if (a.enabled) {
                anchorBoneToBodyPart(
                        pose,
                        a.bone,
                        a.bodyPart,
                        a.reverse,
                        a.original,
                        new btVector3(PZbtVector3.btVector3Zero));
            }
        }
    }

    /**
     * @00163a10
     */
    public void updateAnchors(SkeletonPose pose) {
        loadAnchors(pose);
    }

    /**
     * @00163a20
     */
    public void create(SkeletonPose pose) {
        int n = pose.m_skeleton.m_boneCount;
        resize(m_boneToBodyPart, n, -1);
        for (int i = 0; i < n; i++) {
            m_boneToBodyPart.set(i, -1);
        }
        while (m_bodies.size() < BODY_PART_COUNT) {
            m_bodies.add(null);
        }
        while (m_bodies.size() > BODY_PART_COUNT) {
            m_bodies.remove(m_bodies.size() - 1);
        }
        updateBodyParts(pose);
        loadAnchors(pose);
        loadConstraints();
    }

    private static void resize(ArrayList<Integer> list, int n, int fill) {
        while (list.size() < n) {
            list.add(fill);
        }
        while (list.size() > n) {
            list.remove(list.size() - 1);
        }
    }

    private static void logDebug(int line, String msg) {
        PZDebugLog.instance().log("PZBullet", PZDebugType.Debug, LOC + line, msg);
    }

    private static void logError(int line, String msg) {
        PZDebugLog.instance().log("PZBullet", PZDebugType.Error, LOC + line, msg);
    }
}
