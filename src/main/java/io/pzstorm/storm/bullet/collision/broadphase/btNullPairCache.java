// Port of BulletCollision/BroadphaseCollision/btOverlappingPairCache.h (Bullet 2.82), class
// btNullPairCache (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;

/**
 * btNullPairCache skips add/removal of overlapping pairs. Userful for benchmarking and unit
 * testing.
 */
public class btNullPairCache extends btOverlappingPairCache {
    public final btAlignedObjectArray<btBroadphasePair> m_overlappingPairArray =
            btBroadphasePair.newArray();

    @Override
    public btAlignedObjectArray<btBroadphasePair> getOverlappingPairArrayPtr() {
        return m_overlappingPairArray;
    }

    @Override
    public btAlignedObjectArray<btBroadphasePair> getOverlappingPairArray() {
        return m_overlappingPairArray;
    }

    @Override
    public void cleanOverlappingPair(btBroadphasePair pair, btDispatcher dispatcher) {}

    @Override
    public int getNumOverlappingPairs() {
        return 0;
    }

    @Override
    public void cleanProxyFromPairs(btBroadphaseProxy proxy, btDispatcher dispatcher) {}

    @Override
    public void setOverlapFilterCallback(btOverlapFilterCallback callback) {}

    @Override
    public void processAllOverlappingPairs(btOverlapCallback callback, btDispatcher dispatcher) {}

    @Override
    public btBroadphasePair findPair(btBroadphaseProxy proxy0, btBroadphaseProxy proxy1) {
        return null;
    }

    @Override
    public boolean hasDeferredRemoval() {
        return true;
    }

    @Override
    public void setInternalGhostPairCallback(btOverlappingPairCallback ghostPairCallback) {}

    @Override
    public btBroadphasePair addOverlappingPair(btBroadphaseProxy proxy0, btBroadphaseProxy proxy1) {
        return null;
    }

    @Override
    public Object removeOverlappingPair(
            btBroadphaseProxy proxy0, btBroadphaseProxy proxy1, btDispatcher dispatcher) {
        return null;
    }

    @Override
    public void removeOverlappingPairsContainingProxy(
            btBroadphaseProxy proxy0, btDispatcher dispatcher) {}

    @Override
    public void sortOverlappingPairs(btDispatcher dispatcher) {}

    /** Implicit destructor: destroys m_overlappingPairArray. */
    @Override
    public void destroy() {
        m_overlappingPairArray.clear();
        super.destroy();
    }
}
