// Port of PZ glue RagdollBuilder (RagdollBuilder.cpp; @00183b90..@00185870).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.collision.shapes.btBoxShape;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.collision.shapes.btCompoundShape;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.ArrayList;

/** Singleton (function-local static in {@code RagdollBuilder::Instance()}), 0x108 bytes. */
public class RagdollBuilder {

    private static final String LOC = "/usr/src/pz/pzbullet/RagdollBuilder.cpp:";

    /** +0 */
    public PZRagdollScript m_script;

    /** +8 root rotation (set by initializeSkeleton). */
    public final btTransform m_rootTransform = new BoneTransform();

    /** +0x88 vector&lt;int&gt; skeletonHierarchy (parent per bone). */
    public final ArrayList<Integer> m_skeletonHierarchy = new ArrayList<>();

    /** +0xa0 vector&lt;BonePair*&gt; (11). */
    public final ArrayList<BonePair> m_bonePairs = new ArrayList<>();

    /** +0xb8 vector&lt;BoneInfo*&gt; */
    public final ArrayList<BoneInfo> m_bones = new ArrayList<>();

    /** +0xd0 */
    public Skeleton m_defaultSkeleton;

    /** +0xd8 vector&lt;btCollisionShape*&gt; (11). */
    public final ArrayList<btCollisionShape> m_bodyPartShapes = new ArrayList<>();

    /** +0xf0 */
    public double m_mass = 70.0;

    /** +0xf8 */
    public double m_friction = 1.5;

    /** +0x100 */
    public double m_rollingFriction = 0.5;

    private static RagdollBuilder s_instance;

    /**
     * @00183b90
     */
    public RagdollBuilder() {
        m_script = new PZRagdollScript();
    }

    /** RagdollBuilder::Instance(): lazy function-local static. */
    public static synchronized RagdollBuilder Instance() {
        if (s_instance == null) {
            s_instance = new RagdollBuilder();
        }
        return s_instance;
    }

    /** Test hook: drop the singleton (the C++ static lives for the process). */
    static synchronized void resetInstanceForTests() {
        s_instance = null;
    }

    private static void log(String line, String msg) {
        PZDebugLog.instance().log("PZBullet", PZDebugType.Debug, LOC + line, msg);
    }

    private static <T> void resize(ArrayList<T> v, int n, java.util.function.Supplier<T> f) {
        while (v.size() > n) {
            v.remove(v.size() - 1);
        }
        while (v.size() < n) {
            v.add(f.get());
        }
    }

    /**
     * @00184950
     */
    public void initialize() {
        log("10", "RagdollBuilder::initialize");
        createBonePairs();
        log("12", "[Success] RagdollBuilder::initialize");
    }

    /**
     * @00184700
     */
    public void createBonePairs() {
        if (!m_bonePairs.isEmpty()) {
            log("19", "[Already Initialized] RagdollBuilder::createBonePairs");
            return;
        }
        log("23", "RagdollBuilder::createBonePairs");
        resize(m_bonePairs, 0xb, () -> null);
        m_bonePairs.set(0, new BonePair(4, 5));
        m_bonePairs.set(1, new BonePair(5, 6));
        m_bonePairs.set(2, new BonePair(5, 6));
        m_bonePairs.set(3, new BonePair(0x14, 0x15));
        m_bonePairs.set(4, new BonePair(0x15, 0x16));
        m_bonePairs.set(5, new BonePair(0x18, 0x19));
        m_bonePairs.set(6, new BonePair(0x19, 0x1a));
        m_bonePairs.set(7, new BonePair(8, 9));
        m_bonePairs.set(8, new BonePair(9, 10));
        m_bonePairs.set(9, new BonePair(0xe, 0xf));
        m_bonePairs.set(10, new BonePair(0xf, 0x10));
        log("36", "[Success] RagdollBuilder::createBonePairs");
    }

    /**
     * @001849c0
     */
    public void initializeSkeletonHiearachy(int n, int[] hierarchy) {
        log("41", "RagdollBuilder::initializeSkeletonHiearachy");
        resize(m_skeletonHierarchy, Math.max(n, 0), () -> 0);
        for (int i = 0; i < n; i++) {
            m_skeletonHierarchy.set(i, hierarchy[i]);
            PZDebugLog.instance()
                    .logf(
                            "PZBullet",
                            PZDebugType.Trace,
                            LOC + "50",
                            "RagdollBuilder::skeletonHierarchy[%d] = %d",
                            i,
                            m_skeletonHierarchy.get(i));
        }
        log("52", "[Success] RagdollBuilder::initializeSkeletonHiearachy");
    }

    /**
     * @00184b10
     */
    public void setupDefaulSkeleton(int n) {
        log("57", "RagdollBuilder::setupDefaulSkeleton");
        if (!m_bones.isEmpty()) {
            log("60", "[Already Initialized] RagdollBuilder::setupDefaulSkeleton");
            return;
        }
        if (n != 0) {
            resize(m_bones, n, () -> null);
            for (int i = 0; i < n; i++) {
                BoneInfo b = new BoneInfo();
                m_bones.set(i, b);
                b.index = i;
                int parent = m_skeletonHierarchy.get(i);
                if (parent != -1) {
                    b.parent = m_bones.get(parent);
                }
            }
        }
        log("76", "[Success] RagdollBuilder::setupDefaulSkeleton");
    }

    /**
     * @00185010
     */
    public void initializeSkeleton(int n, float[] p, float qx, float qy, float qz, float qw) {
        log("81", "RagdollBuilder::initializeSkeleton");
        m_rootTransform.m_basis.setRotation(
                new btQuaternion((double) qx, (double) qy, (double) qz, (double) qw));
        m_rootTransform.m_origin.setValue(0.0, 0.0, 0.0);
        setupDefaulSkeleton(n);
        int off = 0;
        for (int i = 0; i < n; i++) {
            BoneInfo b = m_bones.get(i);
            btQuaternion q =
                    new btQuaternion(
                            (double) p[off + 3],
                            (double) p[off + 4],
                            (double) p[off + 5],
                            (double) p[off + 6]);
            btVector3 o = new btVector3((double) p[off], (double) p[off + 1], (double) p[off + 2]);
            b.m_localTransform.set(new btTransform(q, o));
            off += 7;
        }
        // bones[0] (m_bones.begin()) takes the root rotation for both transforms, origin 0.
        BoneInfo b0 = m_bones.get(0);
        b0.m_transform.m_basis.set(m_rootTransform.m_basis);
        b0.m_transform.m_origin.setValue(0.0, 0.0, 0.0);
        b0.m_localTransform.m_basis.set(m_rootTransform.m_basis);
        b0.m_localTransform.m_origin.setValue(0.0, 0.0, 0.0);
        for (int i = 1; i < n; i++) {
            BoneInfo b = m_bones.get(i);
            b.m_transform.set(b.parent.m_transform.mul(b.m_localTransform));
        }
        createDefaultSkeleton(n);
        updateBodyPartShapes();
        log("125", "[Success] RagdollBuilder::initializeSkeleton");
    }

    /**
     * @00183d70
     */
    public void createDefaultSkeleton(int n) {
        log("130", "RagdollBuilder::createDefaultSkeleton");
        ArrayList<btTransform> transforms = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            transforms.add(new btTransform(m_bones.get(i).m_localTransform));
        }
        ArrayList<Integer> parents = new ArrayList<>(m_skeletonHierarchy);
        m_defaultSkeleton = new Skeleton(parents, null, transforms);
        if (m_defaultSkeleton == null) {
            log(
                    "141",
                    "[Failure] RagdollBuilder::createDefaultSkeleton - defaultSkeleton == nullptr");
        } else {
            log("145", "[Success] RagdollBuilder::createDefaultSkeleton");
        }
    }

    /**
     * @00184110
     */
    public double getBodyPartLength(int part) {
        BonePair pair = m_bonePairs.get(part);
        BoneInfo b = m_bones.get(pair.second);
        BoneInfo a = m_bones.get(pair.first);
        double dy = a.m_transform.m_origin.y() - b.m_transform.m_origin.y();
        double dx = a.m_transform.m_origin.x() - b.m_transform.m_origin.x();
        double dz = a.m_transform.m_origin.z() - b.m_transform.m_origin.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double getFriction() {
        return m_friction;
    }

    public double getRollingFriction() {
        return m_rollingFriction;
    }

    public void setFriction(double friction, double rollingFriction) {
        m_friction = friction;
        m_rollingFriction = rollingFriction;
    }

    public double getMass() {
        return m_mass;
    }

    public void setMass(double mass) {
        m_mass = mass;
    }

    public RagdollConstraint getRagdollConstraint(int joint) {
        return m_script.getRagdollConstraint(joint);
    }

    public RagdollAnchor getRagdollAnchor(int bone) {
        return m_script.getRagdollAnchor(bone);
    }

    public BodyPartInfo getRagdollBodyPartInfo(int part) {
        return m_script.getBodyPartInfo(part);
    }

    public ArrayList<RagdollAnchor> getRagdollAnchors() {
        return m_script.getRagdollAnchors();
    }

    /**
     * @00184210 createBodyPartShape(int, SkeletonPose*): the pose argument is unused. Returns a
     * btCompoundShape(true) with one child (capsule/box/sphere) at identity rotation, origin =
     * info.offset.
     */
    public btCompoundShape createBodyPartShape(int part, SkeletonPose pose) {
        BodyPartInfo info = getRagdollBodyPartInfo(part);
        Instance();
        double height;
        if (!info.calculateLength) {
            height = (double) info.height;
        } else {
            height = getBodyPartLength(info.part);
            height -= (double) info.gap;
        }
        btCollisionShape child = null;
        if (info.shape == 1) {
            double r = (double) info.radius;
            child = new btBoxShape(new btVector3(r, r, r));
        } else if (info.shape == 2) {
            child = new btSphereShape((double) info.radius);
        } else if (info.shape == 0) {
            child = new PZCapsuleShape((double) info.radius, height);
        }
        btTransform t = new btTransform();
        t.setIdentity();
        t.m_origin.set(info.offset);
        btCompoundShape compound = new btCompoundShape(true);
        compound.addChildShape(t, child);
        return compound;
    }

    public btCollisionShape getBodyPartCollisionShape(int part) {
        return m_bodyPartShapes.get(part);
    }

    /**
     * @00184490
     */
    public double getBodyPartMass(int part) {
        BodyPartInfo info = getRagdollBodyPartInfo(part);
        return m_mass * info.mass;
    }

    /**
     * @001844b0
     */
    public void configureBodyDynamics(int part, btRigidBody body) {
        RagdollBodyDynamics d = m_script.getRagdollBodyDynamics(part);
        body.setDamping((double) d.linearDamping, (double) d.angularDamping);
        body.m_deactivationTime = (double) d.deactivationTime;
        body.m_angularSleepingThreshold = (double) d.angularSleepingThreshold;
        body.m_friction = (double) d.friction;
        body.m_linearSleepingThreshold = (double) d.linearSleepingThreshold;
        body.m_updateRevision += 2;
        body.m_rollingFriction = (double) d.rollingFriction;
    }

    /**
     * @00184550
     */
    public SkeletonPose createSkeletonPose() {
        if (m_defaultSkeleton == null) {
            log(
                    "158",
                    "[Failure] RagdollBuilder::createSkeletonPose - RagdollBuilder::defaultSkeleton == nullptr");
            return null;
        }
        return new SkeletonPose(m_defaultSkeleton);
    }

    /**
     * @00184ca0
     */
    public void updateBodyPartShapes() {
        log("212", "RagdollBuilder::updateBodyPartShapes");
        resize(m_bodyPartShapes, 0xb, () -> null);
        // A temporary SkeletonPose of the default skeleton is built and destroyed (no effect).
        SkeletonPose pose = new SkeletonPose(m_defaultSkeleton);
        for (int i = 0; i < 0xb; i++) {
            btCollisionShape old = m_bodyPartShapes.get(i);
            if (old != null) {
                if (old instanceof btCompoundShape) {
                    btCompoundShape c = (btCompoundShape) old;
                    for (int j = c.getNumChildShapes() - 1; j != -1; j--) {
                        c.removeChildShapeByIndex(j);
                    }
                }
            }
            m_bodyPartShapes.set(i, createBodyPartShape(i, null));
        }
        log("231", "[Success] RagdollBuilder::updateBodyPartShapes");
    }
}
