// Port of BulletCollision/BroadphaseCollision/btQuantizedBvh.h (Bullet 2.82), class
// btBvhSubtreeInfo (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

/**
 * btBvhSubtreeInfo provides info to gather a subtree of limited size. Value type: C++ {@code a = b}
 * is {@link #assign(btBvhSubtreeInfo)}. {@code m_padding[3]} is not represented (never read).
 */
public class btBvhSubtreeInfo {
    /** sizeof(btBvhSubtreeInfo) in the binary. */
    public static final int SIZEOF = 32;

    // 12 bytes
    public final int[] m_quantizedAabbMin = new int[3];
    public final int[] m_quantizedAabbMax = new int[3];
    // 4 bytes, points to the root of the subtree
    public int m_rootNodeIndex;
    // 4 bytes
    public int m_subtreeSize;

    public btBvhSubtreeInfo() {
        // memset(&m_padding[0], 0, sizeof(m_padding));
    }

    /** Implicit copy-assignment. */
    public btBvhSubtreeInfo assign(btBvhSubtreeInfo other) {
        m_quantizedAabbMin[0] = other.m_quantizedAabbMin[0];
        m_quantizedAabbMin[1] = other.m_quantizedAabbMin[1];
        m_quantizedAabbMin[2] = other.m_quantizedAabbMin[2];
        m_quantizedAabbMax[0] = other.m_quantizedAabbMax[0];
        m_quantizedAabbMax[1] = other.m_quantizedAabbMax[1];
        m_quantizedAabbMax[2] = other.m_quantizedAabbMax[2];
        m_rootNodeIndex = other.m_rootNodeIndex;
        m_subtreeSize = other.m_subtreeSize;
        return this;
    }

    public void setAabbFromQuantizeNode(btQuantizedBvhNode quantizedNode) {
        m_quantizedAabbMin[0] = quantizedNode.m_quantizedAabbMin[0];
        m_quantizedAabbMin[1] = quantizedNode.m_quantizedAabbMin[1];
        m_quantizedAabbMin[2] = quantizedNode.m_quantizedAabbMin[2];
        m_quantizedAabbMax[0] = quantizedNode.m_quantizedAabbMax[0];
        m_quantizedAabbMax[1] = quantizedNode.m_quantizedAabbMax[1];
        m_quantizedAabbMax[2] = quantizedNode.m_quantizedAabbMax[2];
    }
}
