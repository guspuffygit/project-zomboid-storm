// Port of BulletCollision/BroadphaseCollision/btOverlappingPairCache.h (Bullet 2.82), struct
// btOverlapFilterCallback (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

public abstract class btOverlapFilterCallback {
    /** {@code virtual ~btOverlapFilterCallback() {}} */
    public void destroy() {}

    /** Return true when pairs need collision. */
    public abstract boolean needBroadphaseCollision(
            btBroadphaseProxy proxy0, btBroadphaseProxy proxy1);
}
