// Port of BulletCollision/BroadphaseCollision/btQuantizedBvh.h (Bullet 2.82), struct
// btOptimizedBvhNode (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * btOptimizedBvhNode contains both internal and leaf node information. 64 bytes in the double
 * build. Value type: C++ {@code a = b} is {@link #assign(btOptimizedBvhNode)}. {@code
 * m_padding[20]} is not represented (never read).
 */
public class btOptimizedBvhNode {
    /** sizeof(btOptimizedBvhNode) in the binary (double precision). */
    public static final int SIZEOF = 96;

    // 32 bytes
    public final btVector3 m_aabbMinOrg = new btVector3();
    public final btVector3 m_aabbMaxOrg = new btVector3();

    // 4
    public int m_escapeIndex;

    // 8
    // for child nodes
    public int m_subPart;
    public int m_triangleIndex;

    public btOptimizedBvhNode() {}

    /** Implicit copy-assignment. */
    public btOptimizedBvhNode assign(btOptimizedBvhNode other) {
        m_aabbMinOrg.set(other.m_aabbMinOrg);
        m_aabbMaxOrg.set(other.m_aabbMaxOrg);
        m_escapeIndex = other.m_escapeIndex;
        m_subPart = other.m_subPart;
        m_triangleIndex = other.m_triangleIndex;
        return this;
    }
}
