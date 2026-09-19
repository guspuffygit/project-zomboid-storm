// Port of BulletCollision/BroadphaseCollision/btOverlappingPairCache.h (Bullet 2.82), struct
// btOverlapCallback (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

public abstract class btOverlapCallback {
    /** {@code virtual ~btOverlapCallback() {}} */
    public void destroy() {}

    /** Return true for deletion of the pair. */
    public abstract boolean processOverlap(btBroadphasePair pair);
}
