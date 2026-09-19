// Port of BulletCollision/BroadphaseCollision/btOverlappingPairCache.h (Bullet 2.82), class
// btOverlappingPairCache and the BT_NULL_PAIR constant (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;

/**
 * The btOverlappingPairCache provides an interface for overlapping pair management (add, remove,
 * storage), used by the btBroadphaseInterface broadphases. The btHashedOverlappingPairCache and
 * btSortedOverlappingPairCache classes are two implementations.
 *
 * <p>{@code getOverlappingPairArrayPtr()} returns a {@code btBroadphasePair*} into the array in
 * C++; in Java it returns the array itself (index from 0).
 */
public abstract class btOverlappingPairCache extends btOverlappingPairCallback {
    /** {@code const int BT_NULL_PAIR = 0xffffffff;} */
    public static final int BT_NULL_PAIR = 0xffffffff;

    /** {@code virtual ~btOverlappingPairCache() {}} */
    @Override
    public void destroy() {
        super.destroy();
    }

    public abstract btAlignedObjectArray<btBroadphasePair> getOverlappingPairArrayPtr();

    public abstract btAlignedObjectArray<btBroadphasePair> getOverlappingPairArray();

    public abstract void cleanOverlappingPair(btBroadphasePair pair, btDispatcher dispatcher);

    public abstract int getNumOverlappingPairs();

    public abstract void cleanProxyFromPairs(btBroadphaseProxy proxy, btDispatcher dispatcher);

    public abstract void setOverlapFilterCallback(btOverlapFilterCallback callback);

    public abstract void processAllOverlappingPairs(
            btOverlapCallback callback, btDispatcher dispatcher);

    public abstract btBroadphasePair findPair(btBroadphaseProxy proxy0, btBroadphaseProxy proxy1);

    public abstract boolean hasDeferredRemoval();

    public abstract void setInternalGhostPairCallback(btOverlappingPairCallback ghostPairCallback);

    public abstract void sortOverlappingPairs(btDispatcher dispatcher);
}
