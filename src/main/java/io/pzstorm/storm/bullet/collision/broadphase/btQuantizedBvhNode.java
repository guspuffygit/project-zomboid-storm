// Port of BulletCollision/BroadphaseCollision/btQuantizedBvh.h (Bullet 2.82), struct
// btQuantizedBvhNode (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

/**
 * btQuantizedBvhNode is a compressed aabb node, 16 bytes. Node can be used for leafnode or internal
 * node. Leafnodes can point to 32-bit triangle index (non-negative range).
 *
 * <p>Value type: C++ {@code a = b} is {@link #assign(btQuantizedBvhNode)}. The {@code unsigned
 * short} arrays are held as {@code int} in the range 0..65535.
 */
public class btQuantizedBvhNode {
    /** sizeof(btQuantizedBvhNode) in the binary. */
    public static final int SIZEOF = 16;

    // 12 bytes
    public final int[] m_quantizedAabbMin = new int[3];
    public final int[] m_quantizedAabbMax = new int[3];
    // 4 bytes
    public int m_escapeIndexOrTriangleIndex;

    public btQuantizedBvhNode() {}

    /** Implicit copy-assignment. */
    public btQuantizedBvhNode assign(btQuantizedBvhNode other) {
        m_quantizedAabbMin[0] = other.m_quantizedAabbMin[0];
        m_quantizedAabbMin[1] = other.m_quantizedAabbMin[1];
        m_quantizedAabbMin[2] = other.m_quantizedAabbMin[2];
        m_quantizedAabbMax[0] = other.m_quantizedAabbMax[0];
        m_quantizedAabbMax[1] = other.m_quantizedAabbMax[1];
        m_quantizedAabbMax[2] = other.m_quantizedAabbMax[2];
        m_escapeIndexOrTriangleIndex = other.m_escapeIndexOrTriangleIndex;
        return this;
    }

    public boolean isLeafNode() {
        // skipindex is negative (internal node), triangleindex >=0 (leafnode)
        return (m_escapeIndexOrTriangleIndex >= 0);
    }

    public int getEscapeIndex() {
        return -m_escapeIndexOrTriangleIndex;
    }

    public int getTriangleIndex() {
        int x = 0;
        int y = (~(x & 0)) << (31 - btQuantizedBvh.MAX_NUM_PARTS_IN_BITS);
        // Get only the lower bits where the triangle index is stored
        return (m_escapeIndexOrTriangleIndex & ~(y));
    }

    public int getPartId() {
        // Get only the highest bits where the part index is stored
        return (m_escapeIndexOrTriangleIndex >> (31 - btQuantizedBvh.MAX_NUM_PARTS_IN_BITS));
    }
}
