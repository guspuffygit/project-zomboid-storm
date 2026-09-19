// Port of BulletCollision/BroadphaseCollision/btQuantizedBvh.h (Bullet 2.82), class
// btNodeOverlapCallback (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

public abstract class btNodeOverlapCallback {
    /** {@code virtual ~btNodeOverlapCallback() {}} */
    public void destroy() {}

    public abstract void processNode(int subPart, int triangleIndex);
}
