// Port of PZ glue Skeleton (Skeleton.cpp; Skeleton::Skeleton @0017e6b0, Skeleton::initialize
// @0017dc40).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.linearmath.btTransform;
import java.util.ArrayList;
import java.util.List;

/** 0x50 bytes. */
public class Skeleton {

    /** Skeleton::Bone (0x20): int parent (-1 default), vector&lt;int&gt; children. */
    public static class Bone {
        /** +0 */
        public int parent = -1;

        /** +8 */
        public final ArrayList<Integer> children = new ArrayList<>();
    }

    /** +0 vector&lt;BoneTransform&gt;: parent-relative bind pose per bone. */
    public final ArrayList<BoneTransform> m_bindPoseRelative = new ArrayList<>();

    /** +0x18 vector&lt;BoneTransform&gt;: inverse of the absolute bind pose per bone. */
    public final ArrayList<BoneTransform> m_bindPoseAbsoluteInverse = new ArrayList<>();

    /** +0x30 */
    public int m_boneCount;

    /** +0x38 vector&lt;Bone&gt; */
    public final ArrayList<Bone> m_bones = new ArrayList<>();

    /**
     * @0017e6b0 Skeleton(std::vector&lt;int&gt; parents, const char** names,
     * std::vector&lt;btTransform&gt; transforms). The vectors are passed by value (copied); names
     * is unused.
     */
    public Skeleton(List<Integer> parents, String[] names, List<? extends btTransform> transforms) {
        PZDebugLog.instance()
                .log(
                        "PZBullet",
                        PZDebugType.Debug,
                        "/usr/src/pz/pzbullet/Skeleton.cpp:6",
                        "Skeleton::Skeleton");
        ArrayList<btTransform> tCopy = new ArrayList<>(transforms.size());
        for (btTransform t : transforms) {
            tCopy.add(new btTransform(t));
        }
        ArrayList<Integer> pCopy = new ArrayList<>(parents);
        initialize(pCopy, names, tCopy);
        PZDebugLog.instance()
                .log(
                        "PZBullet",
                        PZDebugType.Debug,
                        "/usr/src/pz/pzbullet/Skeleton.cpp:8",
                        "[Success] Skeleton::Skeleton");
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
     * @0017dc40
     */
    public void initialize(
            List<Integer> parents, String[] names, List<? extends btTransform> transforms) {
        PZDebugLog.instance()
                .log(
                        "PZBullet",
                        PZDebugType.Debug,
                        "/usr/src/pz/pzbullet/Skeleton.cpp:13",
                        "Skeleton::initialize");
        int n = transforms.size();
        m_boneCount = n;
        resize(m_bones, n, Bone::new);
        resize(m_bindPoseRelative, n, BoneTransform::new);
        resize(m_bindPoseAbsoluteInverse, n, BoneTransform::new);
        for (int i = 0; i < m_boneCount; i++) {
            Bone b = m_bones.get(i);
            b.parent = parents.get(i);
            b.children.clear();
            for (int j = i + 1; j < m_boneCount; j++) {
                if (parents.get(j) == i) {
                    b.children.add(j);
                }
            }
            m_bindPoseRelative.get(i).set(transforms.get(i));
        }
        // abs[0] = btTransform(rel[0].getRotation(), rel[0].getOrigin()); crashes in C++ when n ==
        // 0.
        btTransform rel0 = m_bindPoseRelative.get(0);
        BoneTransform abs0 = m_bindPoseAbsoluteInverse.get(0);
        abs0.set(new btTransform(rel0.getRotation(), rel0.getOrigin()));
        for (int i = 1; i < m_boneCount; i++) {
            int parent = m_bones.get(i).parent;
            m_bindPoseAbsoluteInverse
                    .get(i)
                    .set(m_bindPoseAbsoluteInverse.get(parent).mul(m_bindPoseRelative.get(i)));
        }
        for (int i = 0; i < m_boneCount; i++) {
            BoneTransform t = m_bindPoseAbsoluteInverse.get(i);
            t.set(t.inverse());
        }
        PZDebugLog.instance()
                .log(
                        "PZBullet",
                        PZDebugType.Debug,
                        "/usr/src/pz/pzbullet/Skeleton.cpp:48",
                        "[Success] Skeleton::initialize");
    }
}
