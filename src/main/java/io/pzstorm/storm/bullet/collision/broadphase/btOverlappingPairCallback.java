// Port of BulletCollision/BroadphaseCollision/btOverlappingPairCallback.h (Bullet 2.82)
// (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

/**
 * The btOverlappingPairCallback class is an additional optional broadphase user callback for
 * adding/removing overlapping pairs, similar interface to btOverlappingPairCache.
 */
public abstract class btOverlappingPairCallback {
    /** {@code virtual ~btOverlappingPairCallback() {}} */
    public void destroy() {}

    public abstract btBroadphasePair addOverlappingPair(
            btBroadphaseProxy proxy0, btBroadphaseProxy proxy1);

    /** Returns the C++ {@code void*} (the removed pair's m_internalInfo1). */
    public abstract Object removeOverlappingPair(
            btBroadphaseProxy proxy0, btBroadphaseProxy proxy1, btDispatcher dispatcher);

    public abstract void removeOverlappingPairsContainingProxy(
            btBroadphaseProxy proxy0, btDispatcher dispatcher);
}
