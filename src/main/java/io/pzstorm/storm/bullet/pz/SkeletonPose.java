// Port of PZ glue SkeletonPose (SkeletonPose.cpp; @0017f500..@00183b90).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import java.util.ArrayList;
import java.util.List;

/**
 * 0x70 bytes: +0 Skeleton*, +8 std::string (never read by the ported code), +0x28 local (animation)
 * transforms, +0x40 absolute pose, +0x58 relative pose, +0x68 zero.
 */
public class SkeletonPose {

    /** +0 */
    public final Skeleton m_skeleton;

    /** +0x28 per-bone local transform relative to the bind pose. */
    public final ArrayList<BoneTransform> m_localTransforms = new ArrayList<>();

    /** +0x40 per-bone absolute (model-space) pose. */
    public final ArrayList<BoneTransform> m_absoluteTransforms = new ArrayList<>();

    /** +0x58 per-bone parent-relative pose (bindPoseRelative * local). */
    public final ArrayList<BoneTransform> m_relativeTransforms = new ArrayList<>();

    /** Inlined ctor (RagdollBuilder::createSkeletonPose @00184550): three identity vectors. */
    public SkeletonPose(Skeleton skeleton) {
        m_skeleton = skeleton;
        int n = skeleton.m_boneCount;
        for (int i = 0; i < n; i++) {
            m_localTransforms.add(new BoneTransform());
            m_absoluteTransforms.add(new BoneTransform());
            m_relativeTransforms.add(new BoneTransform());
        }
    }

    public Skeleton getSkeleton() {
        return m_skeleton;
    }

    /** BoneTransform(const btTransform&amp;): rotation round-tripped through a quaternion. */
    private static void assignViaRotation(btTransform dst, btTransform src) {
        btQuaternion q = src.getRotation();
        btTransform tmp = new btTransform(q, src.getOrigin());
        dst.set(tmp);
    }

    /**
     * @0017f500
     */
    public void update() {
        int n = m_skeleton.m_boneCount;
        for (int i = 0; i < n; i++) {
            m_relativeTransforms
                    .get(i)
                    .set(m_skeleton.m_bindPoseRelative.get(i).mul(m_localTransforms.get(i)));
        }
        assignViaRotation(m_absoluteTransforms.get(0), m_relativeTransforms.get(0));
        for (int i = 1; i < n; i++) {
            int parent = m_skeleton.m_bones.get(i).parent;
            m_absoluteTransforms
                    .get(i)
                    .set(m_absoluteTransforms.get(parent).mul(m_relativeTransforms.get(i)));
        }
    }

    /**
     * @00180c40
     */
    public void updateBonePoseRelative(int i) {
        m_relativeTransforms
                .get(i)
                .set(m_skeleton.m_bindPoseRelative.get(i).mul(m_localTransforms.get(i)));
    }

    /**
     * @00180ed0 (returns a copy)
     */
    public btTransform getBonePoseRelative(int i) {
        updateBonePoseRelative(i);
        return new btTransform(m_relativeTransforms.get(i));
    }

    /**
     * @00180f50 (recursive over the parent chain)
     */
    public void updateBonePoseAbsolute(int i) {
        updateBonePoseRelative(i);
        int parent = m_skeleton.m_bones.get(i).parent;
        if (parent > -1) {
            updateBonePoseAbsolute(parent);
            m_absoluteTransforms
                    .get(i)
                    .set(m_absoluteTransforms.get(parent).mul(m_relativeTransforms.get(i)));
            return;
        }
        assignViaRotation(m_absoluteTransforms.get(i), m_relativeTransforms.get(i));
    }

    /**
     * @00181600 (returns a copy)
     */
    public btTransform getBonePoseAbsolute(int i) {
        updateBonePoseAbsolute(i);
        return new btTransform(m_absoluteTransforms.get(i));
    }

    /**
     * @00181680 local[i] = BoneTransform(normalizedRotation(bind[i]^-1 * parentAbs^-1 * t)).
     */
    public void setBonePoseAbsolute(int i, btTransform t) {
        btTransform bind = m_skeleton.m_bindPoseRelative.get(i);
        btTransform parentAbs;
        if (m_skeleton.m_bones.get(i).parent < 0) {
            parentAbs = btTransform.getIdentity();
        } else {
            parentAbs = getBonePoseAbsolute(m_skeleton.m_bones.get(i).parent);
        }
        btTransform local = bind.inverse().mul(parentAbs.inverse()).mul(t);
        btQuaternion q = local.getRotation();
        q.normalize();
        local.m_basis.setRotation(q);
        assignViaRotation(m_localTransforms.get(i), local);
    }

    /**
     * @00182bb0 local[i].basis = rotation of (bindRot^-1 * parentRot^-1 * q), normalised, then
     * round-tripped through a matrix; origin kept.
     */
    public void setBoneRotationAbsolute(int i, btQuaternion q) {
        btTransform bind = m_skeleton.m_bindPoseRelative.get(i);
        btTransform parentAbs;
        if (m_skeleton.m_bones.get(i).parent < 0) {
            parentAbs = btTransform.getIdentity();
        } else {
            parentAbs = getBonePoseAbsolute(m_skeleton.m_bones.get(i).parent);
        }
        btQuaternion qb = bind.getRotation();
        btQuaternion qp = parentAbs.getRotation();
        btQuaternion r = qb.inverse().mul(qp.inverse()).mul(q);
        r.normalize();
        btMatrix3x3 m = new btMatrix3x3();
        m.setRotation(r);
        BoneTransform local = m_localTransforms.get(i);
        local.m_basis.setRotation(m.getRotation());
    }

    /**
     * @00182a90
     */
    public void setSkeletonPose(List<? extends btTransform> transforms) {
        int n = m_skeleton.m_boneCount;
        for (int i = 0; i < n; i++) {
            setBonePoseAbsolute(i, new btTransform(transforms.get(i)));
        }
    }

    /**
     * @00182b40
     */
    public void resetBoneTransforms() {
        int n = m_skeleton.m_boneCount;
        for (int i = 0; i < n; i++) {
            m_localTransforms.get(i).setIdentity();
        }
    }
}
