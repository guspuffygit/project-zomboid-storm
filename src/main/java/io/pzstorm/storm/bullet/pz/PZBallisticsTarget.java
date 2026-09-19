// Port of PZ PZBallisticsTarget (PZBallisticsTarget.cpp) from decomp @001725d0 (ctor),
// @0016e9b0..@00172870 (methods), static init @00148660
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionWorld;
import io.pzstorm.storm.bullet.collision.shapes.btBoxShape;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.collision.shapes.btCompoundShape;
import io.pzstorm.storm.bullet.dynamics.btDynamicsWorld;
import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.ArrayList;

/**
 * A shootable ragdoll target: owns a {@link Ragdoll} driven from a skeleton pose, and an
 * "adjusting" box collision object (user pointer: BulletObject type Ragdoll=11) that is resized to
 * enclose the ragdoll body parts every update.
 *
 * <p>Static-init quirk: in the NoOpenGL lib .init_array runs {@code
 * _GLOBAL__sub_I_PZBallisticsTarget} (@00148660) before {@code _GLOBAL__sub_I_PZbtVector3}
 * (@00148730), so {@link #allPartsColor} and {@link #extentsScale} are copied from the still-zero
 * PZbtVector3 statics (Cyan / Half in the source) and start as (0,0,0,0) until the JNI setters are
 * called.
 */
public class PZBallisticsTarget {

    /** +0x08 lazily created by {@link #getAdjustingTargetShape()}. */
    public btBoxShape m_adjustingShape;

    /** +0x10 (double)0.175f: box half extent x/z. */
    public double m_halfWidth = (double) 0.175f;

    /** +0x18 (double)0.8165f: box half extent y. */
    public double m_halfHeight = (double) 0.8165f;

    /** +0x20 */
    public double m_unk20 = 0.0;

    /** +0x28 (double)0.8165f */
    public double m_unk28 = (double) 0.8165f;

    /** +0x30 */
    public double m_unk30 = 0.0;

    /** +0x38 */
    public double m_unk38 = 0.0;

    /** +0x40 */
    public int m_id = -1;

    /** +0x48 */
    public Ragdoll m_ragdoll;

    /** +0x50 */
    public SkeletonPose m_skeletonPose;

    /** +0x58 */
    public btCollisionObject m_targetingCollision;

    /** +0x60 */
    public final btVector3 m_position = new btVector3(PZbtVector3.btVector3Zero);

    /** +0x80 root transform used for bone 0 in updateSkeletonBoneTransforms. */
    public final btTransform m_transform = new btTransform();

    /** static btBoxShape* targetingShape */
    public static btBoxShape targetingShape;

    /** static btVector3 allPartsColor (zero: init-order quirk, see class doc). */
    public static final btVector3 allPartsColor = new btVector3(0.0, 0.0, 0.0);

    /** static btVector3 extentsScale (zero: init-order quirk; stored but never read). */
    public static final btVector3 extentsScale = new btVector3(0.0, 0.0, 0.0);

    public static boolean useIsometricAabb = false;
    public static boolean drawAllParts = true;
    public static boolean drawTargetParts = true;
    public static boolean drawTargetCollision = true;
    public static double isometricHeightScale = 1.0;

    /**
     * @001725d0
     */
    public PZBallisticsTarget(int id) {
        m_transform.set(btTransform.getIdentity());
        m_id = id;
        m_skeletonPose = RagdollBuilder.Instance().createSkeletonPose();
        m_ragdoll = new Ragdoll();
        m_ragdoll.create(m_skeletonPose);
        createTargetingCollision();
    }

    /**
     * @0016e9b0
     */
    public int getId() {
        return m_id;
    }

    /**
     * @00171d20
     */
    public SkeletonPose getSkeletonPose() {
        return m_skeletonPose;
    }

    /**
     * @0016e9c0
     */
    public void updateBodyParts() {
        m_ragdoll.updateBodyParts(m_skeletonPose);
    }

    /**
     * @00171750
     */
    public btBoxShape getAdjustingTargetShape() {
        if (m_adjustingShape != null) {
            return m_adjustingShape;
        }
        m_adjustingShape = new btBoxShape(new btVector3(m_halfWidth, m_halfHeight, m_halfWidth));
        return m_adjustingShape;
    }

    /**
     * @001717d0
     */
    public btBoxShape getTargetingShape() {
        if (targetingShape != null) {
            return targetingShape;
        }
        targetingShape = new btBoxShape(new btVector3(m_halfWidth, m_halfHeight, m_halfWidth));
        return targetingShape;
    }

    /**
     * @0016f580
     */
    public boolean ignoreBodyPartAabb(int part) {
        return part == 1 || part == 3 || part == 5 || part == 7 || part == 9;
    }

    /**
     * @0016e9d0: basis = rot(z,p3) * rot(y,p2) * rot(x,p1); origin (and w) zeroed.
     */
    public void setAxis(float p1, float p2, float p3) {
        btQuaternion qx = new btQuaternion(new btVector3(1.0, 0.0, 0.0), (double) p1);
        btQuaternion qy = new btQuaternion(new btVector3(0.0, 1.0, 0.0), (double) p2);
        btQuaternion qz = new btQuaternion(new btVector3(0.0, 0.0, 1.0), (double) p3);
        btQuaternion r = qz.mul(qy).mul(qx);
        m_transform.m_basis.setRotation(r);
        m_transform.m_origin.x = 0.0;
        m_transform.m_origin.y = 0.0;
        m_transform.m_origin.z = 0.0;
        m_transform.m_origin.w = 0.0;
    }

    /**
     * @0016ed70
     */
    public btVector3 projectIsometric(btVector3 v) {
        double x = v.x;
        double z = v.z;
        double h = v.y * isometricHeightScale;
        btVector3 r = new btVector3(x - z, h + (x + z) * 0.5, 0.0);
        r.w = 0.0;
        return r;
    }

    /** PZ minsd(acc, v): {@code acc < v ? acc : v}. */
    static double minsd(double acc, double v) {
        return acc < v ? acc : v;
    }

    /** PZ maxsd(acc, v): {@code acc > v ? acc : v}. */
    static double maxsd(double acc, double v) {
        return acc > v ? acc : v;
    }

    private static btMatrix3x3 inverseCameraBasis() {
        return new btMatrix3x3(PZBallistics.cameraAimTransform.m_basis.getRotation().inverse());
    }

    private static final double FLT_MAX_D = 3.4028234663852886e38;

    /**
     * @0016edc0: projects the 8 corners of a 3D AABB onto rows 0/1 of the inverse camera-aim
     * rotation (no origin subtraction); x from row 0, y from row 1; z,w of both outputs are 0.
     */
    public void computeIsometricAabbFrom3D(
            btVector3 min3, btVector3 max3, btVector3 outMin, btVector3 outMax) {
        btMatrix3x3 m = inverseCameraBasis();
        outMin.x = FLT_MAX_D;
        outMin.y = FLT_MAX_D;
        outMin.z = 0.0;
        outMin.w = 0.0;
        outMax.x = -FLT_MAX_D;
        outMax.y = -FLT_MAX_D;
        outMax.z = 0.0;
        outMax.w = 0.0;
        for (int k = 0; k < 8; k++) {
            btVector3 c =
                    new btVector3(
                            (k & 1) != 0 ? max3.x : min3.x,
                            (k & 2) != 0 ? max3.y : min3.y,
                            (k & 4) != 0 ? max3.z : min3.z);
            double a = m.m_el0.dot(c);
            double b = m.m_el1.dot(c);
            outMin.x = minsd(outMin.x, a);
            outMin.y = minsd(outMin.y, b);
            outMax.x = maxsd(outMax.x, a);
            outMax.y = maxsd(outMax.y, b);
        }
    }

    private static int bodyPartInfoPart(int i) {
        return RagdollBuilder.Instance().getRagdollBodyPartInfo(i).part;
    }

    /** Running accumulators shared by the AABB loops. */
    private static final class Acc {
        double minX = PZbtVector3.btVector3MaxPositive.x;
        double minY = PZbtVector3.btVector3MaxPositive.y;
        double minZ = PZbtVector3.btVector3MaxPositive.z;
        double maxX = PZbtVector3.btVector3MaxNegative.x;
        double maxY = PZbtVector3.btVector3MaxNegative.y;
        double maxZ = PZbtVector3.btVector3MaxNegative.z;
        // isometric (rows of the inverse camera basis)
        double min0 = FLT_MAX_D;
        double max0 = -FLT_MAX_D;
        double min1 = FLT_MAX_D;
        double max1 = -FLT_MAX_D;

        void add3D(btVector3 mn, btVector3 mx) {
            minX = minsd(minX, mn.x);
            minY = minsd(minY, mn.y);
            minZ = minsd(minZ, mn.z);
            maxX = maxsd(maxX, mx.x);
            maxY = maxsd(maxY, mx.y);
            maxZ = maxsd(maxZ, mx.z);
        }

        /** 8 corners (bit0 x, bit1 y, bit2 z) relative to the camera origin, rows 0/1. */
        void addIso(btVector3 mn, btVector3 mx, btMatrix3x3 m, btVector3 o) {
            for (int k = 0; k < 8; k++) {
                btVector3 p =
                        new btVector3(
                                ((k & 1) != 0 ? mx.x : mn.x) - o.x,
                                ((k & 2) != 0 ? mx.y : mn.y) - o.y,
                                ((k & 4) != 0 ? mx.z : mn.z) - o.z);
                double a = m.m_el1.dot(p);
                double b = m.m_el0.dot(p);
                min1 = minsd(min1, a);
                max1 = maxsd(max1, a);
                min0 = minsd(min0, b);
                max0 = maxsd(max0, b);
            }
        }
    }

    /**
     * Visits every AABB used by the target AABB computations: for each of the 11 body parts not
     * ignored whose rigid body has a shape, the shape's AABB at the body's world transform, or for
     * a compound shape each child's AABB (children in reverse order).
     */
    private void forEachPartAabb(Acc acc, btMatrix3x3 iso, btVector3 isoOrigin) {
        for (int i = 0; i < 11; i++) {
            int part = bodyPartInfoPart(i);
            if (ignoreBodyPartAabb(part)) {
                continue;
            }
            btRigidBody body = m_ragdoll.getRigidBody(part);
            btCollisionShape shape = body.m_collisionShape;
            if (shape == null) {
                continue;
            }
            btTransform t = new btTransform(body.m_worldTransform);
            btVector3 mn = new btVector3();
            btVector3 mx = new btVector3();
            if (!(shape instanceof btCompoundShape)) {
                shape.getAabb(t, mn, mx);
                acc.add3D(mn, mx);
                if (iso != null) {
                    acc.addIso(mn, mx, iso, isoOrigin);
                }
                continue;
            }
            btCompoundShape compound = (btCompoundShape) shape;
            for (int j = compound.getNumChildShapes() - 1; j >= 0; j--) {
                btTransform ct = t.mul(compound.getChildTransform(j));
                compound.getChildShape(j).getAabb(ct, mn, mx);
                acc.add3D(mn, mx);
                if (iso != null) {
                    acc.addIso(mn, mx, iso, isoOrigin);
                }
            }
        }
    }

    /**
     * @0016f5c0: world AABB of the body parts, box half extents = size * 0.45f.
     */
    public void computeTargetAabb() {
        Acc acc = new Acc();
        forEachPartAabb(acc, null, null);
        btBoxShape s = m_adjustingShape;
        s.m_implicitShapeDimensions.x = (acc.maxX - acc.minX) * 0.44999998807907104;
        s.m_implicitShapeDimensions.y = (acc.maxY - acc.minY) * 0.44999998807907104;
        s.m_implicitShapeDimensions.z = (acc.maxZ - acc.minZ) * 0.44999998807907104;
        s.m_implicitShapeDimensions.w = 0.0;
        btVector3 center =
                new btVector3(
                        (acc.maxX + acc.minX) * 0.5,
                        (acc.minY + acc.maxY) * 0.5,
                        (acc.maxZ + acc.minZ) * 0.5);
        m_targetingCollision.setWorldTransform(new btTransform(btQuaternion.getIdentity(), center));
    }

    /**
     * @0016fe40: like computeTargetAabb, but each part AABB's 8 corners (relative to the camera aim
     * origin) are also projected on rows 0/1 of the inverse camera rotation; the box gets half
     * extents (row0 range * 0.4f, row1 range * 0.45f, 1.0) and is oriented to face the camera; the
     * centre is the 3D AABB centre.
     */
    public void computeIsometricTargetAabb() {
        btMatrix3x3 m = inverseCameraBasis();
        btVector3 o = new btVector3(PZBallistics.cameraAimTransform.m_origin);
        Acc acc = new Acc();
        forEachPartAabb(acc, m, o);
        btBoxShape s = m_adjustingShape;
        if (s != null) {
            s.m_implicitShapeDimensions.x = (acc.max0 - acc.min0) * 0.4000000059604645;
            s.m_implicitShapeDimensions.y = (acc.max1 - acc.min1) * 0.44999998807907104;
            s.m_implicitShapeDimensions.z = 1.0;
            s.m_implicitShapeDimensions.w = 0.0;
        }
        btVector3 f = m.mul(PZbtVector3.btVector3Forward);
        btVector3 u = m.mul(PZbtVector3.btVector3Up);
        btVector3 r = u.cross(f);
        r.normalize();
        btVector3 u2 = f.cross(r);
        u2.normalize();
        f.normalize();
        btMatrix3x3 basis = new btMatrix3x3(r.x, r.y, r.z, u2.x, u2.y, u2.z, f.x, f.y, f.z);
        btQuaternion rot = basis.getRotation();
        btVector3 center =
                new btVector3(
                        (acc.minX + acc.maxX) * 0.5,
                        (acc.minY + acc.maxY) * 0.5,
                        (acc.maxZ + acc.minZ) * 0.5);
        m_targetingCollision.setWorldTransform(new btTransform(rot, center));
    }

    /**
     * @001716b0 (qx..qw and b are unused).
     */
    public void update(
            float x, float y, float z, float qx, float qy, float qz, float qw, boolean b) {
        m_position.x = x;
        m_position.y = y;
        m_position.z = z;
        m_position.w = 0.0;
        m_ragdoll.m_position.set(m_position);
        if (m_targetingCollision == null) {
            return;
        }
        m_targetingCollision.setCollisionShape(m_adjustingShape);
        if (useIsometricAabb) {
            computeIsometricTargetAabb();
            return;
        }
        computeTargetAabb();
    }

    private static btDynamicsWorld world() {
        return (btDynamicsWorld) btGlobals.gDynamicsWorld;
    }

    /**
     * @00171850: 1 if already in the world, else adds it (group 0x10, mask -1) and returns 0.
     */
    public int addToWorld() {
        btDynamicsWorld w = world();
        btAlignedObjectArray<btCollisionObject> objs = w.getCollisionObjectArray();
        for (int i = 0; i < objs.size(); i++) {
            if (objs.get(i) == m_targetingCollision) {
                return 1;
            }
        }
        w.addCollisionObject(m_targetingCollision, (short) 0x10, (short) -1);
        return 0;
    }

    /**
     * @001718c0: 0 if removed, 1 if not in the world.
     */
    public int removeFromWorld() {
        btDynamicsWorld w = world();
        btAlignedObjectArray<btCollisionObject> objs = w.getCollisionObjectArray();
        for (int i = 0; i < objs.size(); i++) {
            if (objs.get(i) == m_targetingCollision) {
                w.removeCollisionObject(m_targetingCollision);
                return 0;
            }
        }
        return 1;
    }

    /**
     * @00171920 (no null check on the collision object itself).
     */
    public void resetTargetingCollision() {
        BulletObject bo = (BulletObject) m_targetingCollision.getUserPointer();
        if (bo != null) {
            bo.id = m_id;
        }
    }

    /**
     * @00171940
     */
    public void reset(int id) {
        m_id = id;
        resetTargetingCollision();
        m_transform.set(btTransform.getIdentity());
    }

    /**
     * @00171ac0
     */
    public void removeBodyPartCollision() {
        for (int i = 0; i < 11; i++) {
            btRigidBody body = m_ragdoll.getRigidBody(i);
            if (body != null) {
                world().removeCollisionObject(body);
            }
        }
    }

    /**
     * @00172870
     */
    public void addBodyPartCollision() {
        for (int i = 0; i < 11; i++) {
            btRigidBody body = m_ragdoll.getRigidBody(i);
            if (body != null) {
                BulletObject bo = (BulletObject) body.getUserPointer();
                body.m_collisionFlags = 4;
                if (bo == null) {
                    bo = new BulletObject(null, 0.0, 0.0, 0.0, BulletObjectType.RagdollPart);
                }
                bo.partIndex = i;
                bo.id = m_id;
                body.setUserPointer(bo);
                world().addCollisionObject(body, (short) 0x10, (short) -1);
            }
        }
    }

    /**
     * @00171b10: first body part (0..10) flagged hit, else 11.
     */
    public int getTargetedBodyPart() {
        for (int i = 0; i < 11; i++) {
            btRigidBody body = m_ragdoll.getRigidBody(i);
            if (body != null) {
                BulletObject bo = (BulletObject) body.getUserPointer();
                if (bo != null && bo.flag40) {
                    return i;
                }
            }
        }
        return 11;
    }

    /**
     * @00172510
     */
    public void createTargetingCollision() {
        btCollisionObject obj = new btCollisionObject();
        m_targetingCollision = obj;
        obj.setCollisionShape(getAdjustingTargetShape());
        m_targetingCollision.m_collisionFlags = 4;
        BulletObject bo = new BulletObject(null, 0.0, 0.0, 0.0, BulletObjectType.Ragdoll);
        bo.color.set(PZbtVector3.Cyan);
        bo.id = m_id;
        m_targetingCollision.setUserPointer(bo);
    }

    /**
     * @00171b60
     */
    public void debugDrawTargetCollision(btCollisionWorld world) {
        btCollisionObject obj = m_targetingCollision;
        BulletObject bo = (BulletObject) obj.getUserPointer();
        btVector3 color = new btVector3(PZbtVector3.Red);
        if (bo != null && bo.flag40) {
            color.set(bo.color);
            bo.flag40 = false;
        }
        world.debugDrawObject(obj.m_worldTransform, obj.m_collisionShape, color);
    }

    /**
     * @00171bd0
     */
    public void debugDrawTargetParts(btCollisionWorld world) {
        for (int i = 0; i < 11; i++) {
            btRigidBody body = m_ragdoll.getRigidBody(i);
            if (body != null) {
                BulletObject bo = (BulletObject) body.getUserPointer();
                if (bo != null && bo.flag40) {
                    world.debugDrawObject(body.m_worldTransform, body.m_collisionShape, bo.color);
                    bo.flag40 = false;
                }
            }
        }
    }

    /**
     * @00171c40
     */
    public void debugDrawAllParts(btCollisionWorld world) {
        for (int i = 0; i < 11; i++) {
            btRigidBody body = m_ragdoll.getRigidBody(i);
            if (body != null) {
                world.debugDrawObject(body.m_worldTransform, body.m_collisionShape, allPartsColor);
            }
        }
    }

    /**
     * @00171c90
     */
    public void debugDraw(btCollisionWorld world) {
        if (drawAllParts) {
            debugDrawAllParts(world);
        }
        if (drawTargetParts) {
            debugDrawTargetParts(world);
        }
        if (drawTargetCollision) {
            debugDrawTargetCollision(world);
        }
    }

    /**
     * @00171d10 {@code debugDraw(gDynamicsWorld)}
     */
    public void render() {
        debugDraw(world());
    }

    private static int boneParent(SkeletonPose pose, int i) {
        return pose.m_skeleton.m_bones.get(i).parent;
    }

    /**
     * @00171d30: {@code p} holds n bones of 7 floats (x, y, z, qx, qy, qz, qw), parent-relative.
     * Bone 0 is replaced by {@link #m_transform} (its input is ignored); bones 1..n-1 are chained
     * through the skeleton's parent indices. With n == 0 the C++ writes through a null vector
     * pointer (crash); Java throws.
     */
    public void updateSkeletonBoneTransforms(int n, float[] p) {
        if (n == 0) {
            throw new IllegalStateException(
                    "PZBallisticsTarget::updateSkeletonBoneTransforms with 0 bones (C++ crashes)");
        }
        ArrayList<btTransform> local = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            btTransform t = new btTransform();
            int b = 7 * i;
            t.m_origin.x = p[b];
            t.m_origin.y = p[b + 1];
            t.m_origin.z = p[b + 2];
            t.m_origin.w = 0.0;
            t.m_basis.setRotation(new btQuaternion(p[b + 3], p[b + 4], p[b + 5], p[b + 6]));
            local.add(t);
        }
        ArrayList<btTransform> world = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            world.add(new btTransform());
        }
        world.get(0).set(m_transform);
        local.get(0).set(m_transform);
        SkeletonPose pose = m_skeletonPose;
        for (int i = 1; i < n; i++) {
            int parent = boneParent(pose, i);
            world.get(i).set(world.get(parent).mul(local.get(i)));
        }
        ArrayList<btTransform> copy = new ArrayList<>(n);
        for (btTransform t : world) {
            copy.add(new btTransform(t));
        }
        pose.setSkeletonPose(copy);
        m_ragdoll.updateBodiesFromSkeleton(pose);
    }
}
