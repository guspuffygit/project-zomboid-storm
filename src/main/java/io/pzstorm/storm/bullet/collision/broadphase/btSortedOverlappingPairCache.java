// Port of BulletCollision/BroadphaseCollision/btOverlappingPairCache.cpp and
// btOverlappingPairCache.h (Bullet 2.82), class btSortedOverlappingPairCache.
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;

/**
 * btSortedOverlappingPairCache maintains the objects with overlapping AABB. Typically managed by
 * the Broadphase, Axis3Sweep or btSimpleBroadphase.
 */
public class btSortedOverlappingPairCache extends btOverlappingPairCache {
    /** avoid brute-force finding all the time */
    public final btAlignedObjectArray<btBroadphasePair> m_overlappingPairArray =
            btBroadphasePair.newArray();

    /** during the dispatch, check that user doesn't destroy/create proxy */
    public boolean m_blockedForChanges;

    /** by default, do the removal during the pair traversal */
    public boolean m_hasDeferredRemoval;

    /** if set, use the callback instead of the built in filter in needBroadphaseCollision */
    public btOverlapFilterCallback m_overlapFilterCallback;

    public btOverlappingPairCallback m_ghostPairCallback;

    public btSortedOverlappingPairCache() {
        m_blockedForChanges = false;
        m_hasDeferredRemoval = true;
        m_overlapFilterCallback = null;
        m_ghostPairCallback = null;
        int initialAllocatedSize = 2;
        m_overlappingPairArray.reserve(initialAllocatedSize);
    }

    /** Empty user destructor; then m_overlappingPairArray is destroyed. */
    @Override
    public void destroy() {
        m_overlappingPairArray.clear();
        super.destroy();
    }

    @Override
    public void processAllOverlappingPairs(btOverlapCallback callback, btDispatcher dispatcher) {
        int i;

        for (i = 0; i < m_overlappingPairArray.size(); ) {
            btBroadphasePair pair = m_overlappingPairArray.get(i);
            if (callback.processOverlap(pair)) {
                cleanOverlappingPair(pair, dispatcher);
                pair.m_pProxy0 = null;
                pair.m_pProxy1 = null;
                m_overlappingPairArray.swap(i, m_overlappingPairArray.size() - 1);
                m_overlappingPairArray.pop_back();
                btGlobals.gOverlappingPairs--;
            } else {
                i++;
            }
        }
    }

    @Override
    public Object removeOverlappingPair(
            btBroadphaseProxy proxy0, btBroadphaseProxy proxy1, btDispatcher dispatcher) {
        if (!hasDeferredRemoval()) {
            btBroadphasePair findPair = new btBroadphasePair(proxy0, proxy1);

            int findIndex = m_overlappingPairArray.findLinearSearch(findPair);
            if (findIndex < m_overlappingPairArray.size()) {
                btGlobals.gOverlappingPairs--;
                btBroadphasePair pair = m_overlappingPairArray.get(findIndex);
                Object userData = pair.m_internalInfo1;
                cleanOverlappingPair(pair, dispatcher);
                if (m_ghostPairCallback != null)
                    m_ghostPairCallback.removeOverlappingPair(proxy0, proxy1, dispatcher);

                // Upstream bug kept: swaps with capacity()-1, not size()-1. The slot past size() is
                // uninitialized memory in C++; materialize it so the value swap can run.
                int last = m_overlappingPairArray.capacity() - 1;
                if (m_overlappingPairArray.m_data[last] == null) {
                    m_overlappingPairArray.m_data[last] = new btBroadphasePair();
                }
                m_overlappingPairArray.swap(findIndex, last);
                m_overlappingPairArray.pop_back();
                return userData;
            }
        }

        return null;
    }

    @Override
    public void cleanOverlappingPair(btBroadphasePair pair, btDispatcher dispatcher) {
        if (pair.m_algorithm != null) {
            {
                pair.m_algorithm.destroy();
                dispatcher.freeCollisionAlgorithm(pair.m_algorithm.m_allocAddress);
                pair.m_algorithm = null;
                btGlobals.gRemovePairs--;
            }
        }
    }

    @Override
    public btBroadphasePair addOverlappingPair(btBroadphaseProxy proxy0, btBroadphaseProxy proxy1) {
        // don't add overlap with own

        if (!needsBroadphaseCollision(proxy0, proxy1)) return null;

        btBroadphasePair mem = m_overlappingPairArray.expandNonInitializing();
        btBroadphasePair pair = mem.init(proxy0, proxy1);

        btGlobals.gOverlappingPairs++;
        btGlobals.gAddedPairs++;

        if (m_ghostPairCallback != null) m_ghostPairCallback.addOverlappingPair(proxy0, proxy1);
        return pair;
    }

    /**
     * this findPair becomes really slow. Either sort the list to speedup the query, or use a
     * different solution. It is mainly used for Removing overlapping pairs. Removal could be
     * delayed.
     */
    @Override
    public btBroadphasePair findPair(btBroadphaseProxy proxy0, btBroadphaseProxy proxy1) {
        if (!needsBroadphaseCollision(proxy0, proxy1)) return null;

        btBroadphasePair tmpPair = new btBroadphasePair(proxy0, proxy1);
        int findIndex = m_overlappingPairArray.findLinearSearch(tmpPair);

        if (findIndex < m_overlappingPairArray.size()) {
            btBroadphasePair pair = m_overlappingPairArray.get(findIndex);
            return pair;
        }
        return null;
    }

    /** Local class {@code CleanPairCallback} of cleanProxyFromPairs. */
    private static final class CleanPairCallback extends btOverlapCallback {
        private final btBroadphaseProxy m_cleanProxy;
        private final btOverlappingPairCache m_pairCache;
        private final btDispatcher m_dispatcher;

        CleanPairCallback(
                btBroadphaseProxy cleanProxy,
                btOverlappingPairCache pairCache,
                btDispatcher dispatcher) {
            m_cleanProxy = cleanProxy;
            m_pairCache = pairCache;
            m_dispatcher = dispatcher;
        }

        @Override
        public boolean processOverlap(btBroadphasePair pair) {
            if ((pair.m_pProxy0 == m_cleanProxy) || (pair.m_pProxy1 == m_cleanProxy)) {
                m_pairCache.cleanOverlappingPair(pair, m_dispatcher);
            }
            return false;
        }
    }

    @Override
    public void cleanProxyFromPairs(btBroadphaseProxy proxy, btDispatcher dispatcher) {
        CleanPairCallback cleanPairs = new CleanPairCallback(proxy, this, dispatcher);

        processAllOverlappingPairs(cleanPairs, dispatcher);
    }

    /** Local class {@code RemovePairCallback} of removeOverlappingPairsContainingProxy. */
    private static final class RemovePairCallback extends btOverlapCallback {
        private final btBroadphaseProxy m_obsoleteProxy;

        RemovePairCallback(btBroadphaseProxy obsoleteProxy) {
            m_obsoleteProxy = obsoleteProxy;
        }

        @Override
        public boolean processOverlap(btBroadphasePair pair) {
            return ((pair.m_pProxy0 == m_obsoleteProxy) || (pair.m_pProxy1 == m_obsoleteProxy));
        }
    }

    @Override
    public void removeOverlappingPairsContainingProxy(
            btBroadphaseProxy proxy, btDispatcher dispatcher) {
        RemovePairCallback removeCallback = new RemovePairCallback(proxy);

        processAllOverlappingPairs(removeCallback, dispatcher);
    }

    public boolean needsBroadphaseCollision(btBroadphaseProxy proxy0, btBroadphaseProxy proxy1) {
        if (m_overlapFilterCallback != null)
            return m_overlapFilterCallback.needBroadphaseCollision(proxy0, proxy1);

        boolean collides = (proxy0.m_collisionFilterGroup & proxy1.m_collisionFilterMask) != 0;
        collides =
                collides && ((proxy1.m_collisionFilterGroup & proxy0.m_collisionFilterMask) != 0);

        return collides;
    }

    @Override
    public btAlignedObjectArray<btBroadphasePair> getOverlappingPairArray() {
        return m_overlappingPairArray;
    }

    @Override
    public btAlignedObjectArray<btBroadphasePair> getOverlappingPairArrayPtr() {
        return m_overlappingPairArray;
    }

    @Override
    public int getNumOverlappingPairs() {
        return m_overlappingPairArray.size();
    }

    public btOverlapFilterCallback getOverlapFilterCallback() {
        return m_overlapFilterCallback;
    }

    @Override
    public void setOverlapFilterCallback(btOverlapFilterCallback callback) {
        m_overlapFilterCallback = callback;
    }

    @Override
    public boolean hasDeferredRemoval() {
        return m_hasDeferredRemoval;
    }

    @Override
    public void setInternalGhostPairCallback(btOverlappingPairCallback ghostPairCallback) {
        m_ghostPairCallback = ghostPairCallback;
    }

    @Override
    public void sortOverlappingPairs(btDispatcher dispatcher) {
        // should already be sorted
    }
}
