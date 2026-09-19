// Port of BulletCollision/BroadphaseCollision/btDbvtBroadphase.h (Bullet 2.82), struct
// btDbvtProxy (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.linearmath.btVector3;

/** sizeof(btDbvtProxy) is 128 in the binary (allocated with btAlignedAlloc(128,16)). */
public class btDbvtProxy extends btBroadphaseProxy {
    public static final int SIZEOF = 128;

    // btDbvtAabbMm aabb;
    public btDbvtNode leaf;
    public final btDbvtProxy[] links = new btDbvtProxy[2];
    public int stage;

    /** Emulated address from btAlignedAlloc (not C++). */
    public long addr;

    public btDbvtProxy(
            btVector3 aabbMin,
            btVector3 aabbMax,
            Object userPtr,
            short collisionFilterGroup,
            short collisionFilterMask) {
        super(aabbMin, aabbMax, userPtr, collisionFilterGroup, collisionFilterMask);
        links[0] = links[1] = null;
    }
}
