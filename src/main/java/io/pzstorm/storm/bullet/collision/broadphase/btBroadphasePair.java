// Port of BulletCollision/BroadphaseCollision/btBroadphaseProxy.h (Bullet 2.82), struct
// btBroadphasePair and its operator== (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;

/**
 * The btBroadphasePair class contains a pair of aabb-overlapping objects. A btDispatcher can search
 * a btCollisionAlgorithm that performs exact/narrowphase collision detection on the actual
 * collision shapes.
 *
 * <p>The C++ anonymous {@code union { void* m_internalInfo1; int m_internalTmpValue; }} is two Java
 * fields. Every write in the linked code writes 0/null to both at once (via the ctors), or writes
 * {@code m_internalTmpValue = 0} right after {@code m_internalInfo1 = 0}; nothing in the binary
 * reads one member after writing the other with a non-zero value.
 */
public class btBroadphasePair {
    public btBroadphaseProxy m_pProxy0;
    public btBroadphaseProxy m_pProxy1;
    public btCollisionAlgorithm m_algorithm;
    public Object m_internalInfo1;
    public int m_internalTmpValue;

    public btBroadphasePair() {
        m_pProxy0 = null;
        m_pProxy1 = null;
        m_algorithm = null;
        m_internalInfo1 = null;
        m_internalTmpValue = 0;
    }

    public btBroadphasePair(btBroadphasePair other) {
        set(other);
    }

    public btBroadphasePair(btBroadphaseProxy proxy0, btBroadphaseProxy proxy1) {
        init(proxy0, proxy1);
    }

    /** Placement {@code new (mem) btBroadphasePair(*proxy0, *proxy1)} into an existing slot. */
    public btBroadphasePair init(btBroadphaseProxy proxy0, btBroadphaseProxy proxy1) {
        if (proxy0.m_uniqueId < proxy1.m_uniqueId) {
            m_pProxy0 = proxy0;
            m_pProxy1 = proxy1;
        } else {
            m_pProxy0 = proxy1;
            m_pProxy1 = proxy0;
        }
        m_algorithm = null;
        m_internalInfo1 = null;
        m_internalTmpValue = 0;
        return this;
    }

    /** Implicit copy-assignment operator (copies the whole 32-byte struct, union included). */
    public btBroadphasePair set(btBroadphasePair other) {
        m_pProxy0 = other.m_pProxy0;
        m_pProxy1 = other.m_pProxy1;
        m_algorithm = other.m_algorithm;
        m_internalInfo1 = other.m_internalInfo1;
        m_internalTmpValue = other.m_internalTmpValue;
        return this;
    }

    /** {@code operator==(const btBroadphasePair&, const btBroadphasePair&)}. */
    public static boolean equals(btBroadphasePair a, btBroadphasePair b) {
        return (a.m_pProxy0 == b.m_pProxy0) && (a.m_pProxy1 == b.m_pProxy1);
    }

    /**
     * {@code btBroadphasePairArray} = {@code btAlignedObjectArray<btBroadphasePair>} (value mode).
     */
    public static btAlignedObjectArray<btBroadphasePair> newArray() {
        return new btAlignedObjectArray<btBroadphasePair>(
                () -> new btBroadphasePair(),
                (dst, src) -> dst.set(src),
                (a, b) -> btBroadphasePair.equals(a, b));
    }
}
